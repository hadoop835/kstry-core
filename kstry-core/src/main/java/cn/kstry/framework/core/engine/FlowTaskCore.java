/*
 *
 *  * Copyright (c) 2020-2023, Lykan (jiashuomeng@gmail.com).
 *  * <p>
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  * <p>
 *  *     http://www.apache.org/licenses/LICENSE-2.0
 *  * <p>
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 *
 */
package cn.kstry.framework.core.engine;

import cn.kstry.framework.core.bpmn.*;
import cn.kstry.framework.core.bpmn.enums.BpmnTypeEnum;
import cn.kstry.framework.core.bpmn.enums.IterateStrategyEnum;
import cn.kstry.framework.core.bpmn.extend.ElementIterable;
import cn.kstry.framework.core.bpmn.impl.BasicElementIterable;
import cn.kstry.framework.core.bus.ContextStoryBus;
import cn.kstry.framework.core.bus.IterDataItem;
import cn.kstry.framework.core.bus.StoryBus;
import cn.kstry.framework.core.component.hook.AsyncFlowHook;
import cn.kstry.framework.core.component.hook.Hook;
import cn.kstry.framework.core.container.component.InvokeProperties;
import cn.kstry.framework.core.container.component.MethodWrapper;
import cn.kstry.framework.core.container.component.TaskServiceDef;
import cn.kstry.framework.core.engine.future.FlowTaskSubscriber;
import cn.kstry.framework.core.engine.interceptor.SubProcessInterceptorRepository;
import cn.kstry.framework.core.engine.interceptor.TaskInterceptorRepository;
import cn.kstry.framework.core.engine.thread.FragmentTask;
import cn.kstry.framework.core.engine.thread.InvokeMethodThreadLocal;
import cn.kstry.framework.core.engine.thread.MonoFlowTask;
import cn.kstry.framework.core.exception.ExceptionEnum;
import cn.kstry.framework.core.exception.KstryException;
import cn.kstry.framework.core.monitor.DemotionInfo;
import cn.kstry.framework.core.monitor.MonitorTracking;
import cn.kstry.framework.core.role.Role;
import cn.kstry.framework.core.util.AssertUtil;
import cn.kstry.framework.core.util.ExceptionUtil;
import cn.kstry.framework.core.util.GlobalUtil;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Mono;

import javax.annotation.Nonnull;
import java.lang.reflect.Array;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * 流程任务执行核心
 *
 * @author lykan
 */
public abstract class FlowTaskCore<T> extends BasicTaskCore<T> {

    private static final Logger LOGGER = LoggerFactory.getLogger(FlowTaskCore.class);

    public FlowTaskCore(StoryEngineModule engineModule, FlowRegister flowRegister, Role role, StoryBus storyBus) {
        super(engineModule, flowRegister, storyBus, role, GlobalUtil.getTaskName(flowRegister.getStartElement(), flowRegister.getRequestId()));
    }

    protected void doExe(Role role, StoryBus storyBus, FlowRegister flowRegister) {
        ContextStoryBus csd;
        Optional<FlowElement> next;
        for (csd = new ContextStoryBus(storyBus),
                     next = flowRegister.nextElement(csd); next.isPresent(); csd = new ContextStoryBus(storyBus), next = flowRegister.nextElement(csd)) {
            FlowElement flowElement = next.get();
            if (!doInvoke(role, storyBus, flowRegister, flowElement)) {
                break;
            }
            Optional<AsyncFlowHook<List<FlowElement>>> asyncFlowHook = flowRegister.predictNextElement(csd, flowElement);
            if (asyncFlowHook.isPresent() && BooleanUtils.isTrue(asyncFlowHook.get().openAsync())) {
                submitAsyncTask(role, storyBus, flowRegister, asyncFlowHook.get());
            }
        }
    }

    private boolean doInvoke(Role role, StoryBus storyBus, FlowRegister flowRegister, FlowElement flowElement) {
        if (flowElement.getElementType() == BpmnTypeEnum.SUB_PROCESS) {
            SubProcess subProcess = GlobalUtil.transferNotEmpty(flowElement, SubProcess.class);
            SubProcessInterceptorRepository subInterceptorRepository = engineModule.getSubInterceptorRepository();
            if (!subInterceptorRepository.postBeforeProcessor(storyBus, subProcess.getStartEvent().getId(), flowRegister.getStoryId())) {
                return true;
            }
            subProcessTaskHandler(role, storyBus, flowRegister, subProcess);
            return false;
        }

        if (flowElement.getElementType() != BpmnTypeEnum.SERVICE_TASK) {
            return true;
        }

        ServiceTask serviceTask = (ServiceTask) flowElement;
        Optional<TaskServiceDef> taskServiceDefOptional = engineModule.getTaskContainer().getTaskServiceDef(serviceTask.getTaskComponent(), serviceTask.getTaskService(), role);
        if (!taskServiceDefOptional.isPresent() && serviceTask.allowAbsent()) {
            return true;
        }
        TaskServiceDef taskServiceDef = taskServiceDefOptional.orElseThrow(() -> ExceptionUtil.buildException(null,
                ExceptionEnum.TASK_SERVICE_MATCH_ERROR, ExceptionEnum.TASK_SERVICE_MATCH_ERROR.getDesc() + GlobalUtil.format(" service task identity: {}", serviceTask.identity())));
        flowRegister.getMonitorTracking().getServiceNodeTracking(flowElement).ifPresent(nodeTracking -> {
            MethodWrapper methodWrapper = taskServiceDef.getMethodWrapper();
            nodeTracking.setThreadId(Thread.currentThread().getName());
            nodeTracking.setMethodName(methodWrapper.getMethod().getName());
            nodeTracking.setTargetName(taskServiceDef.getTaskComponentTarget().getTarget().getClass().getName());
            nodeTracking.setAbility(Optional.ofNullable(methodWrapper.getAbility()).filter(StringUtils::isNotBlank).orElse(null));
        });

        Object result;
        try {
            InvokeMethodThreadLocal.setServiceTask(serviceTask);
            TaskInterceptorRepository taskInterceptorRepository = engineModule.getTaskInterceptorRepository();
            result = taskInterceptorRepository.process(() -> iterateInvokeMethod(serviceTask, taskServiceDef, storyBus, role), taskServiceDef.getGetServiceNodeResource(), storyBus.getScopeDataOperator(), role);
        } catch (Throwable exception) {
            flowRegister.getMonitorTracking().finishTaskTracking(flowElement, exception);
            InvokeProperties invokeProperties = taskServiceDef.getMethodWrapper().getInvokeProperties();
            if (!(serviceTask.strictMode() && invokeProperties.isStrictMode())) {
                LOGGER.info("[{}] Target method execution failure, error is ignored in non-strict mode. identity: {}, exception: {}",
                        ExceptionEnum.SERVICE_INVOKE_ERROR.getExceptionCode(), serviceTask.identity(), exception.getMessage(), exception);
                return true;
            }
            throw exception;
        } finally {
            InvokeMethodThreadLocal.clearServiceTask();
        }
        if (result instanceof Mono) {
            monoResultHandler(role, storyBus, flowRegister, serviceTask, taskServiceDef, result);
            return false;
        }
        storyBus.noticeResult(serviceTask, result, taskServiceDef);
        flowRegister.getMonitorTracking().finishTaskTracking(flowElement, null);
        return true;
    }

    @SuppressWarnings("unchecked")
    private void monoResultHandler(Role role, StoryBus storyBus,
                                   FlowRegister flowRegister, ServiceTask serviceTask, TaskServiceDef taskServiceDef, Object result) {
        InvokeProperties invokeProperties = taskServiceDef.getMethodWrapper().getInvokeProperties();
        boolean strictMode = serviceTask.strictMode() && invokeProperties.isStrictMode();
        Integer timeout = Optional.ofNullable(serviceTask.getTimeout()).filter(t -> t >= 0).orElse(invokeProperties.getTimeout());
        FlowTaskSubscriber flowTaskSubscriber = new FlowTaskSubscriber(
                () -> engineModule.getThreadSwitchHookProcessor().usePreviousData(threadSwitchHookObjectMap, storyBus.getScopeDataOperator()), strictMode, timeout, flowRegister, getTaskName()) {

            @Override
            protected void doNextHook(Object value) {
                resultHandler(flowRegister, serviceTask, storyBus, role, value);
            }

            @Override
            protected void doErrorHook(Throwable throwable) {
                flowRegister.getMonitorTracking().finishTaskTracking(serviceTask, throwable);
                Supplier<Optional<TaskServiceDef>> needDemotionSupplier = getNeedDemotionSupplier(role, invokeProperties);
                Optional<TaskServiceDef> demotionServiceDefOptional = needDemotionSupplier.get();
                KstryException kstryException = ExceptionUtil.buildException(throwable, ExceptionEnum.SERVICE_INVOKE_ERROR, null);
                if (!flowRegister.getAdminFuture().isCancelled(flowRegister.getStartEventId()) && demotionServiceDefOptional.isPresent()) {
                    kstryException.log(e -> LOGGER.warn("[{}] Target method execution failed. identity: {}, taskName: {}, exception: {}",
                            e.getErrorCode(), serviceTask.identity(), getTaskName(), throwable.getMessage(), e)
                    );
                    TaskServiceDef demotionTaskServiceDef = demotionServiceDefOptional.get();
                    DemotionInfo demotionInfo = new DemotionInfo();
                    demotionInfo.setRetryTimes(0);
                    demotionInfo.setDemotionNodeId(demotionTaskServiceDef.getGetServiceNodeResource().getIdentityId());
                    demotionInfo.setDemotionSuccess(true);
                    try {
                        Object o = iterateInvokeMethod(serviceTask, demotionTaskServiceDef, storyBus, role);
                        if (o instanceof Mono) {
                            GlobalUtil.transferNotEmpty(o, Mono.class).subscribe(new BaseSubscriber<Object>() {
                                @Override
                                protected void hookOnNext(@Nonnull Object value) {
                                    engineModule.getThreadSwitchHookProcessor().usePreviousData(threadSwitchHookObjectMap, storyBus.getScopeDataOperator());
                                    doNextHook(value);
                                    dispose();
                                }

                                @Override
                                protected void hookOnError(@Nonnull Throwable e) {
                                    engineModule.getThreadSwitchHookProcessor().usePreviousData(threadSwitchHookObjectMap, storyBus.getScopeDataOperator());
                                    LOGGER.warn("[{}] Target method execution failed, demotion policy execution failed. identity: {}, taskName: {}, exception: {}",
                                            ExceptionEnum.DEMOTION_DEFINITION_ERROR.getExceptionCode(), serviceTask.identity(), getTaskName(), e.getMessage(), e);
                                    dispose();
                                }

                                @Override
                                protected void hookOnComplete() {
                                    engineModule.getThreadSwitchHookProcessor().usePreviousData(threadSwitchHookObjectMap, storyBus.getScopeDataOperator());
                                    doNextHook(null);
                                    dispose();
                                }
                            });
                            return;
                        }
                        doNextHook(o);
                        return;
                    } catch (Throwable e) {
                        LOGGER.warn("[{}] Target method execution failed, demotion policy execution failed. identity: {}, taskName: {}, exception: {}",
                                ExceptionEnum.DEMOTION_DEFINITION_ERROR.getExceptionCode(), serviceTask.identity(), getTaskName(), e.getMessage(), e);
                    } finally {
                        flowRegister.getMonitorTracking().demotionTaskTracking(serviceTask, demotionInfo);
                    }
                }
                if (!isStrictMode()) {
                    kstryException.log(e ->
                            LOGGER.warn("[{}] Target method execution failure, error is ignored in non-strict mode. identity: {}, exception: {}",
                                    e.getErrorCode(), serviceTask.identity(), throwable.getMessage(), e)
                    );
                    doNextElement(flowRegister, serviceTask, storyBus, role);
                    return;
                }
                flowRegister.getAdminFuture().errorNotice(kstryException, flowRegister.getStartEventId());
            }

            @Override
            protected void doCompleteHook() {
                resultHandler(flowRegister, serviceTask, storyBus, role, null);
            }

            private void resultHandler(FlowRegister flowRegister, ServiceTask serviceTask, StoryBus scopeData, Role role, Object value) {
                scopeData.noticeResult(serviceTask, value, taskServiceDef);
                doNextElement(flowRegister, serviceTask, storyBus, role);
            }
        };
        Mono<?> mono = GlobalUtil.transferNotEmpty(result, Mono.class);
        if (timeout != null) {
            mono = mono.timeout(Duration.ofMillis(timeout), Mono.fromRunnable(() -> {
                KstryException e = ExceptionUtil.buildException(null, ExceptionEnum.ASYNC_TASK_TIMEOUT,
                        GlobalUtil.format("Target method execution timeout! maximum time limit: {}ms, identity: {}, taskName: {}",
                                timeout, serviceTask.identity(), GlobalUtil.getTaskName(serviceTask, flowRegister.getRequestId())));
                flowTaskSubscriber.onError(e);
                flowTaskSubscriber.dispose();
            }));
        }
        mono.subscribe(flowTaskSubscriber);
    }

    private void subProcessTaskHandler(Role role, StoryBus storyBus, FlowRegister parentFlowRegister, SubProcess subProcess) {
        StartEvent startEvent = subProcess.getStartEvent();

        FlowRegister cloneSubFlowRegister = parentFlowRegister.cloneSubFlowRegister(startEvent);
        String taskName = GlobalUtil.getTaskName(cloneSubFlowRegister.getStartElement(), cloneSubFlowRegister.getRequestId());
        int timeout = Optional.of(subProcess).map(SubProcess::getTimeout).orElse(storyBus.remainTimeMillis());
        FlowTaskSubscriber flowTaskSubscriber = new FlowTaskSubscriber(
                () -> engineModule.getThreadSwitchHookProcessor().usePreviousData(threadSwitchHookObjectMap, storyBus.getScopeDataOperator()),
                subProcess.strictMode(), timeout, cloneSubFlowRegister, taskName) {

            @Override
            protected void doNextHook(Object value) {
                SubProcessInterceptorRepository subInterceptorRepository = engineModule.getSubInterceptorRepository();
                subInterceptorRepository.postAfterProcessor(storyBus, flowRegister.getStartElement().getId(), flowRegister.getStoryId());
                doNextElement(parentFlowRegister, subProcess, storyBus, role);
            }

            @Override
            protected void doErrorHook(Throwable throwable) {
                SubProcessInterceptorRepository subInterceptorRepository = engineModule.getSubInterceptorRepository();
                subInterceptorRepository.postErrorProcessor(throwable, storyBus, flowRegister.getStartElement().getId(), flowRegister.getStoryId());
                if (!isStrictMode()) {
                    doNextElement(parentFlowRegister, subProcess, storyBus, role);
                }
            }

            @Override
            protected void doCompleteHook() {
                throw ExceptionUtil.buildException(null, ExceptionEnum.STORY_ERROR, null);
            }

            @Override
            protected void doFinallyHook() {
                SubProcessInterceptorRepository subInterceptorRepository = engineModule.getSubInterceptorRepository();
                subInterceptorRepository.postFinallyProcessor(storyBus, flowRegister.getStartElement().getId(), flowRegister.getStoryId());
            }
        };
        MonoFlowTask subFlowTask = new MonoFlowTask(engineModule, cloneSubFlowRegister, role, storyBus, flowTaskSubscriber);
        engineModule.getTaskThreadPool().submitMonoFlowTask(storyBus.getStoryExecutor(), parentFlowRegister.getStartEventId(), subFlowTask);
    }

    private void submitAsyncTask(Role role, StoryBus storyBus, FlowRegister flowRegister, Hook<List<FlowElement>> hook) {
        hook.hook(list -> {
            for (FlowElement asyncFlow : list) {
                SequenceFlow sequenceFlow = (SequenceFlow) asyncFlow;
                FlowRegister asyncFlowRegister = flowRegister.asyncFlowRegister(sequenceFlow);
                FragmentTask fragmentTask = new FragmentTask(engineModule, asyncFlowRegister, role, storyBus);
                engineModule.getTaskThreadPool().submitFragmentTask(storyBus.getStoryExecutor(), fragmentTask);
            }
        }).trigger();
    }

    private void doNextElement(FlowRegister flowRegister, FlowElement flowElement, StoryBus storyBus, Role role) {
        flowRegister.getMonitorTracking().finishTaskTracking(flowElement, null);
        Optional<AsyncFlowHook<List<FlowElement>>> asyncFlowHook = flowRegister.predictNextElement(new ContextStoryBus(storyBus), flowElement);
        if (asyncFlowHook.isPresent() && BooleanUtils.isTrue(asyncFlowHook.get().openAsync())) {
            submitAsyncTask(role, storyBus, flowRegister, asyncFlowHook.get());
        } else {
            FragmentTask fragmentTask = new FragmentTask(engineModule, flowRegister, role, storyBus);
            engineModule.getTaskThreadPool().submitFragmentTask(storyBus.getStoryExecutor(), fragmentTask);
        }
    }

    private Object iterateInvokeMethod(ServiceTask serviceTask, TaskServiceDef taskServiceDef, StoryBus storyBus, Role role) {
        MethodWrapper methodWrapper = taskServiceDef.getMethodWrapper();
        InvokeProperties invokeProperties = methodWrapper.getInvokeProperties();
        ElementIterable elementIterable = getElementIterable(serviceTask, methodWrapper.getElementIterable());
        if (!elementIterable.iterable() || taskServiceDef.isDemotionNode()) {
            return super.retryInvokeMethod(true, null, null, taskServiceDef, serviceTask, storyBus, role);
        }

        MonitorTracking monitorTracking = storyBus.getMonitorTracking();
        Optional<Object> iteData = storyBus.getScopeDataOperator().getData(elementIterable.getIteSource()).map(d -> {
            if (!d.getClass().isArray()) {
                return d;
            }
            int arrLength = Array.getLength(d);
            if (arrLength == 0) {
                return null;
            }
            Object[] dArray = new Object[arrLength];
            for (int i = 0; i < arrLength; i++) {
                dArray[i] = Array.get(d, i);
            }
            return Stream.of(dArray).collect(Collectors.toList());
        }).filter(d -> d instanceof Iterable);
        if (!iteData.isPresent()) {
            monitorTracking.iterateCountTracking(serviceTask, 0, 0);
            LOGGER.info("[{}] {} identity: {}, source: {}", ExceptionEnum.ITERATE_ITEM_ERROR.getExceptionCode(),
                    "Get the target collection is empty, the component will not perform traversal execution!", serviceTask.identity(), elementIterable.getIteSource());
            return null;
        }
        Iterator<?> iterator = GlobalUtil.transferNotEmpty(iteData.get(), Iterable.class).iterator();
        if (!iterator.hasNext()) {
            monitorTracking.iterateCountTracking(serviceTask, 0, 0);
            LOGGER.info("[{}] {} identity: {}, source: {}", ExceptionEnum.ITERATE_ITEM_ERROR.getExceptionCode(),
                    "Get the target collection is empty, the component will not perform traversal execution!", serviceTask.identity(), elementIterable.getIteSource());
            return null;
        }

        List<Object> iteratorList = Lists.newArrayList();
        iterator.forEachRemaining(iteratorList::add);
        List<Object> resultList = Lists.newArrayList();
        List<ImmutablePair<Mono<?>, Integer>> monoResultList = Lists.newArrayList();
        int stride = Optional.ofNullable(elementIterable.getStride()).filter(i -> i > 0).orElse(1);
        boolean isOneStride = stride == 1;
        int size = isOneStride ? iteratorList.size() : (iteratorList.size() / stride + (iteratorList.size() % stride == 0 ? 0 : 1));
        if (notNeedAsyncIterate(methodWrapper, elementIterable) || size <= 1) {
            int count = 0;
            List<Object> batchParamList = isOneStride ? null : Lists.newArrayList();
            for (int i = 0; i < iteratorList.size(); i++) {
                Object next = iteratorList.get(i);
                if (!isOneStride) {
                    batchParamList.add(next);
                    next = batchParamList;
                }
                if (!isOneStride && batchParamList.size() < stride && (i + 1) < iteratorList.size()) {
                    continue;
                }
                int batchParamSize = Optional.ofNullable(batchParamList).map(List::size).orElse(0);
                IterDataItem<Object> iterDataItem = new IterDataItem<>(!isOneStride, isOneStride ? next : null, isOneStride ? Lists.newArrayList() : batchParamList, count++, size);
                Object r = super.retryInvokeMethod(i == 0, elementIterable, iterDataItem, taskServiceDef, serviceTask, storyBus, role);
                if (r == INVOKE_ERROR_SIGN) {
                    if (BooleanUtils.isNotTrue(elementIterable.getIteAlignIndex())) {
                        continue;
                    }
                    if (isOneStride) {
                        resultList.add(null);
                    } else {
                        resultList.addAll(IntStream.range(0, batchParamSize).mapToObj(it -> null).collect(Collectors.toList()));
                    }
                    continue;
                }
                if (elementIterable.getIteStrategy() == IterateStrategyEnum.ANY_SUCCESS) {
                    monitorTracking.iterateCountTracking(serviceTask, count, stride);
                    if (r == null && BooleanUtils.isNotTrue(elementIterable.getIteAlignIndex())) {
                        return resultList;
                    }
                    if (r instanceof Mono) {
                        Integer taskTimeout = getTaskTimeout(false, serviceTask, invokeProperties);
                        try {
                            r = ((Mono<?>) r).block(Duration.ofMillis(taskTimeout == null ? storyBus.remainTimeMillis() : Math.min(taskTimeout, storyBus.remainTimeMillis())));
                        } catch (Exception e) {
                            monitorTracking.timeoutTaskTracking(serviceTask, taskTimeout);
                            throw e;
                        }
                    }
                    addSuccessResult(serviceTask, isOneStride, resultList, elementIterable, r, batchParamSize);
                    return resultList;
                }
                if (methodWrapper.isMonoResult()) {
                    monoResultList.add(ImmutablePair.of((Mono<?>) r, batchParamSize));
                } else {
                    addSuccessResult(serviceTask, isOneStride, resultList, elementIterable, r, batchParamSize);
                }
                if (!isOneStride && (i + 1) < iteratorList.size()) {
                    batchParamList = Lists.newArrayList();
                }
            }
            if (methodWrapper.isMonoResult()) {
                monoResultList.forEach(pair -> {
                    Object res;
                    try {
                        Mono<?> r = pair.getLeft();
                        Integer taskTimeout = getTaskTimeout(false, serviceTask, invokeProperties);
                        try {
                            res = r.block(Duration.ofMillis(taskTimeout == null ? storyBus.remainTimeMillis() : Math.min(taskTimeout, storyBus.remainTimeMillis())));
                        } catch (Exception e) {
                            monitorTracking.timeoutTaskTracking(serviceTask, taskTimeout);
                            throw e;
                        }
                    } catch (Throwable e) {
                        if (elementIterable.getIteStrategy() == IterateStrategyEnum.ALL_SUCCESS) {
                            throw ExceptionUtil.buildException(e, ExceptionEnum.ITERATE_ITEM_ERROR, null);
                        }
                        if (BooleanUtils.isNotTrue(elementIterable.getIteAlignIndex())) {
                            return;
                        }
                        if (isOneStride) {
                            resultList.add(null);
                        } else {
                            resultList.addAll(IntStream.range(0, pair.getRight()).mapToObj(it -> null).collect(Collectors.toList()));
                        }
                        return;
                    }
                    addSuccessResult(serviceTask, isOneStride, resultList, elementIterable, res, pair.getRight());
                });
            }
            monitorTracking.iterateCountTracking(serviceTask, count, stride);
            return resultList;
        }
        return asyncIterate(elementIterable, serviceTask, storyBus, role, taskServiceDef, methodWrapper, monitorTracking, iteratorList, stride, isOneStride);
    }

    private Object asyncIterate(ElementIterable elementIterable, ServiceTask serviceTask, StoryBus storyBus, Role role,
                                TaskServiceDef taskServiceDef, MethodWrapper methodWrapper, MonitorTracking monitorTracking, List<Object> iteratorList, int stride, boolean isOneStride) {
        List<Object> resultList = Lists.newArrayList();
        InvokeProperties invokeProperties = methodWrapper.getInvokeProperties();
        ThreadPoolExecutor executor = StringUtils.isBlank(invokeProperties.getCustomExecutorName())
                ? engineModule.getIteratorThreadPool().getThreadPoolExecutor() : engineModule.getApplicationContext().getBean(invokeProperties.getCustomExecutorName(), ThreadPoolExecutor.class);
        Map<CompletableFuture<Object>, Integer> batchParamSizeMap = Maps.newHashMap();
        List<CompletableFuture<Object>> futureList = Lists.newArrayList();
        if (isOneStride) {
            for (int i = 0; i < iteratorList.size(); i++) {
                int index = i;
                Object next = iteratorList.get(index);
                CompletableFuture<Object> f = CompletableFuture.supplyAsync(() -> {
                    engineModule.getThreadSwitchHookProcessor().usePreviousData(threadSwitchHookObjectMap, storyBus.getScopeDataOperator());
                    return super.retryInvokeMethod(index == 0, elementIterable, new IterDataItem<>(false, next, Lists.newArrayList(), index, iteratorList.size()), taskServiceDef, serviceTask, storyBus, role);
                }, executor);
                futureList.add(f);
            }
        } else {
            List<List<Object>> partition = Lists.partition(iteratorList, stride);
            for (int i = 0; i < partition.size(); i++) {
                int index = i;
                List<Object> next = partition.get(index);
                CompletableFuture<Object> f = CompletableFuture.supplyAsync(() -> {
                    engineModule.getThreadSwitchHookProcessor().usePreviousData(threadSwitchHookObjectMap, storyBus.getScopeDataOperator());
                    return super.retryInvokeMethod(index == 0, elementIterable, new IterDataItem<>(true, null, next, index, partition.size()), taskServiceDef, serviceTask, storyBus, role);
                }, executor);
                futureList.add(f);
                batchParamSizeMap.put(f, next.size());
            }
        }
        monitorTracking.iterateCountTracking(serviceTask, futureList.size(), stride);
        futureList.forEach(f -> {
            Object ro;
            try {
                ro = f.get(storyBus.remainTimeMillis(), TimeUnit.MILLISECONDS);
            } catch (Throwable e) {
                throw ExceptionUtil.buildException(e, ExceptionEnum.ITERATE_ITEM_ERROR, null);
            }
            Integer batchParamSize = batchParamSizeMap.get(f);
            AssertUtil.isTrue(isOneStride || batchParamSize != null);
            if (ro == INVOKE_ERROR_SIGN) {
                if (BooleanUtils.isNotTrue(elementIterable.getIteAlignIndex())) {
                    return;
                }
                if (isOneStride) {
                    resultList.add(null);
                } else {
                    assert batchParamSize != null;
                    resultList.addAll(IntStream.range(0, batchParamSize).mapToObj(it -> null).collect(Collectors.toList()));
                }
                return;
            }
            addSuccessResult(serviceTask, isOneStride, resultList, elementIterable, ro, batchParamSize);
        });
        return resultList;
    }

    private void addSuccessResult(ServiceTask serviceTask, boolean isOneStride, List<Object> resultList, ElementIterable elementIterable, Object ro, Integer batchParamSize) {
        if (isOneStride) {
            if (ro != null || BooleanUtils.isTrue(elementIterable.getIteAlignIndex())) {
                resultList.add(ro);
            }
            return;
        }
        AssertUtil.isTrue(ro instanceof List, ExceptionEnum.ITERATE_ITEM_ERROR,
                "The return value type in batch iteration must be list. identity: {}", serviceTask.identity());
        AssertUtil.isTrue(BooleanUtils.isNotTrue(elementIterable.getIteAlignIndex()) || Objects.equals(((Collection<?>) ro).size(), batchParamSize), ExceptionEnum.ITERATE_ITEM_ERROR,
                "Batch iteration is where the size of return value list must be equal to the number of incoming parameters. expect: {}, actual: {}, identity: {}",
                batchParamSize, ((Collection<?>) ro).size(), serviceTask.identity());
        resultList.addAll(((Collection<?>) ro).stream().filter(ri -> ri != null || BooleanUtils.isTrue(elementIterable.getIteAlignIndex())).collect(Collectors.toList()));
    }

    private ElementIterable getElementIterable(ServiceTask serviceTask, ElementIterable elementIterable) {
        BasicElementIterable iterable = new BasicElementIterable();
        serviceTask.getElementIterable().ifPresent(iterable::mergeProperty);
        iterable.mergeProperty(elementIterable);
        return iterable;
    }
}
