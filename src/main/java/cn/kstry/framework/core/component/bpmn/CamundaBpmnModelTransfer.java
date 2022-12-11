/*
 *
 *  * Copyright (c) 2020-2022, Lykan (jiashuomeng@gmail.com).
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
package cn.kstry.framework.core.component.bpmn;

import cn.kstry.framework.core.bpmn.SequenceFlow;
import cn.kstry.framework.core.bpmn.enums.IterateStrategyEnum;
import cn.kstry.framework.core.bpmn.impl.*;
import cn.kstry.framework.core.component.bpmn.builder.InclusiveJoinPointBuilder;
import cn.kstry.framework.core.component.bpmn.builder.ParallelJoinPointBuilder;
import cn.kstry.framework.core.component.bpmn.builder.SubProcessBuilder;
import cn.kstry.framework.core.component.bpmn.builder.SubProcessLink;
import cn.kstry.framework.core.component.bpmn.joinpoint.InclusiveJoinPoint;
import cn.kstry.framework.core.component.bpmn.joinpoint.ParallelJoinPoint;
import cn.kstry.framework.core.component.bpmn.link.BpmnLink;
import cn.kstry.framework.core.component.bpmn.link.StartBpmnLink;
import cn.kstry.framework.core.component.utils.BasicInStack;
import cn.kstry.framework.core.component.utils.InStack;
import cn.kstry.framework.core.constant.BpmnElementProperties;
import cn.kstry.framework.core.exception.ExceptionEnum;
import cn.kstry.framework.core.resource.config.ConfigResource;
import cn.kstry.framework.core.util.*;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.camunda.bpm.model.bpmn.impl.BpmnModelConstants;
import org.camunda.bpm.model.bpmn.instance.FlowNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Consumer;

/**
 * Camunda 解析器实现
 *
 * @author lykan
 */
public class CamundaBpmnModelTransfer implements BpmnModelTransfer<BpmnModelInstance> {

    private static final Logger LOGGER = LoggerFactory.getLogger(CamundaBpmnModelTransfer.class);

    /**
     * Camunda 中定义的 ServiceTask、Task 类型常量
     */
    private final List<String> CAMUNDA_TASK_TYPE_LIST = Lists.newArrayList(BpmnModelConstants.BPMN_ELEMENT_SERVICE_TASK, BpmnModelConstants.BPMN_ELEMENT_TASK);

    @Override
    public Optional<BpmnLink> getBpmnLink(ConfigResource config, BpmnModelInstance instance, String startId) {
        AssertUtil.notNull(config, ExceptionEnum.CONFIGURATION_PARSE_FAILURE);
        if (instance == null || StringUtils.isBlank(startId)) {
            return Optional.empty();
        }
        org.camunda.bpm.model.bpmn.instance.StartEvent camundaStartEvent = instance.getModelElementById(startId);
        if (camundaStartEvent == null || camundaStartEvent.getParentElement() == null || camundaStartEvent.getParentElement().getElementType() == null
                || Objects.equals(BpmnModelConstants.BPMN_ELEMENT_SUB_PROCESS, camundaStartEvent.getParentElement().getElementType().getTypeName())) {
            return Optional.empty();
        }
        StartBpmnLink bpmnLink = StartBpmnLink.build(camundaStartEvent.getId(), camundaStartEvent.getName());
        doGetStartEvent(config, camundaStartEvent, bpmnLink);
        return Optional.of(bpmnLink);
    }

    @Override
    public Map<String, SubProcessLink> getAllSubProcessLink(ConfigResource config, BpmnModelInstance instance) {
        AssertUtil.notNull(config, ExceptionEnum.CONFIGURATION_PARSE_FAILURE);
        Map<String, SubProcessLink> subProcessMap = Maps.newHashMap();
        if (instance == null) {
            return subProcessMap;
        }

        Collection<org.camunda.bpm.model.bpmn.instance.SubProcess> subProcesses = instance.getModelElementsByType(org.camunda.bpm.model.bpmn.instance.SubProcess.class);
        if (CollectionUtils.isEmpty(subProcesses)) {
            return subProcessMap;
        }
        subProcesses.forEach(sp -> {
            Collection<org.camunda.bpm.model.bpmn.instance.StartEvent> childStartEvent = sp.getChildElementsByType(org.camunda.bpm.model.bpmn.instance.StartEvent.class);
            AssertUtil.oneSize(childStartEvent, ExceptionEnum.CONFIGURATION_SUBPROCESS_ERROR, "SubProcesses are only allowed to also have a start event! fileName: {}", config.getConfigName());
            org.camunda.bpm.model.bpmn.instance.StartEvent startEvent = childStartEvent.iterator().next();

            SubProcessLink subProcessLink = SubProcessLink.build(sp.getId(), sp.getName(), startEvent.getId(), startEvent.getName(), subBpmnLink -> doGetStartEvent(config, startEvent, subBpmnLink));
            ElementPropertyUtil.getNodeProperty(sp, BpmnElementProperties.TASK_STRICT_MODE).map(BooleanUtils::toBooleanObject).filter(b -> !b).ifPresent(b -> subProcessLink.setStrictMode(false));
            ElementPropertyUtil.getNodeProperty(sp, BpmnElementProperties.TASK_TIMEOUT).map(s -> NumberUtils.toInt(s, -1)).filter(i -> i >= 0).ifPresent(subProcessLink::setTimeout);
            fillIterableProperty(config, sp, subProcessLink::setElementIterable);

            SubProcessImpl subProcess = subProcessLink.buildSubDiagramBpmnLink(config).getElement();
            AssertUtil.notTrue(subProcessMap.containsKey(subProcess.getId()), ExceptionEnum.ELEMENT_DUPLICATION_ERROR,
                    "There are duplicate SubProcess ids defined! identity: {}, fileName: {}", subProcess.identity(), config.getConfigName());
            subProcessMap.put(subProcess.getId(), subProcessLink);
        });
        return subProcessMap;
    }

    private void doGetStartEvent(ConfigResource config, org.camunda.bpm.model.bpmn.instance.StartEvent camundaStartEvent, StartBpmnLink bpmnLink) {
        if (camundaStartEvent == null) {
            return;
        }

        Set<org.camunda.bpm.model.bpmn.instance.FlowNode> circularDependencyCheck = Sets.newHashSet();
        Map<org.camunda.bpm.model.bpmn.instance.FlowNode, BpmnLink> nextBpmnLinkMap = Maps.newHashMap();
        nextBpmnLinkMap.put(camundaStartEvent, bpmnLink);

        Map<org.camunda.bpm.model.bpmn.instance.FlowNode, Integer> comingCountMap = Maps.newHashMap();
        InStack<org.camunda.bpm.model.bpmn.instance.FlowNode> basicInStack = new BasicInStack<>();
        basicInStack.push(camundaStartEvent);
        while (!basicInStack.isEmpty()) {
            org.camunda.bpm.model.bpmn.instance.FlowNode element = basicInStack.pop().orElseThrow(() -> ExceptionUtil.buildException(null, ExceptionEnum.SYSTEM_ERROR, null));
            if (element instanceof org.camunda.bpm.model.bpmn.instance.EndEvent) {
                continue;
            }

            BpmnLink beforeBpmnLink = nextBpmnLinkMap.get(element);
            for (org.camunda.bpm.model.bpmn.instance.SequenceFlow seq : element.getOutgoing()) {
                FlowNode targetNode = seq.getTarget();
                if (targetNode instanceof org.camunda.bpm.model.bpmn.instance.EndEvent) {
                    beforeBpmnLink.end(sequenceFlowMapping(config, seq));
                } else if (targetNode instanceof org.camunda.bpm.model.bpmn.instance.ParallelGateway) {
                    BpmnLink parallelBpmnLink = nextBpmnLinkMap.computeIfAbsent(targetNode, node -> {
                        ParallelJoinPointBuilder parallelJoinPointBuilder = bpmnLink.parallel();
                        ElementPropertyUtil.getNodeProperty(targetNode,
                                BpmnElementProperties.ASYNC_ELEMENT_OPEN_ASYNC).map(BooleanUtils::toBoolean).filter(b -> b).ifPresent(b -> parallelJoinPointBuilder.openAsync());
                        ElementPropertyUtil.getNodeProperty(targetNode,
                                BpmnElementProperties.TASK_STRICT_MODE).map(BooleanUtils::toBooleanObject).filter(b -> !b).ifPresent(b -> parallelJoinPointBuilder.notStrictMode());
                        return parallelJoinPointBuilder.build();
                    });
                    BpmnLink nextBpmnLink = beforeBpmnLink.nextParallel(sequenceFlowMapping(config, seq), (ParallelJoinPoint) parallelBpmnLink);
                    nextBpmnLinkMap.put(targetNode, nextBpmnLink);
                } else if (targetNode instanceof org.camunda.bpm.model.bpmn.instance.InclusiveGateway) {
                    BpmnLink inclusiveBpmnLink = nextBpmnLinkMap.computeIfAbsent(targetNode, node -> {
                        InclusiveJoinPointBuilder inclusiveJoinPointBuilder = bpmnLink.inclusive().serviceTask(getServiceTask(node, config));
                        ElementPropertyUtil.getNodeProperty(targetNode,
                                BpmnElementProperties.ASYNC_ELEMENT_OPEN_ASYNC).map(BooleanUtils::toBoolean).filter(b -> b).ifPresent(b -> inclusiveJoinPointBuilder.openAsync());
                        return inclusiveJoinPointBuilder.build();
                    });
                    BpmnLink nextBpmnLink = beforeBpmnLink.nextInclusive(sequenceFlowMapping(config, seq), (InclusiveJoinPoint) inclusiveBpmnLink);
                    nextBpmnLinkMap.put(targetNode, nextBpmnLink);
                } else if (targetNode instanceof org.camunda.bpm.model.bpmn.instance.ExclusiveGateway) {
                    BpmnLink nextBpmnLink = beforeBpmnLink.nextExclusive(sequenceFlowMapping(config, seq)).serviceTask(getServiceTask(targetNode, config)).build();
                    nextBpmnLinkMap.put(targetNode, nextBpmnLink);
                    basicInStack.push(targetNode);
                } else if (targetNode instanceof org.camunda.bpm.model.bpmn.instance.Task && CAMUNDA_TASK_TYPE_LIST.contains(targetNode.getElementType().getTypeName())) {
                    ServiceTaskImpl serviceTask = getServiceTask(targetNode, config);
                    BpmnLink nextBpmnLink = beforeBpmnLink.nextTask(sequenceFlowMapping(config, seq), serviceTask);
                    nextBpmnLinkMap.put(targetNode, nextBpmnLink);
                    basicInStack.push(targetNode);
                } else if (targetNode instanceof org.camunda.bpm.model.bpmn.instance.SubProcess || targetNode instanceof org.camunda.bpm.model.bpmn.instance.CallActivity) {
                    String subProcessId = targetNode.getId();
                    if (targetNode instanceof org.camunda.bpm.model.bpmn.instance.CallActivity) {
                        subProcessId = ((org.camunda.bpm.model.bpmn.instance.CallActivity) targetNode).getCalledElement();
                    }
                    SubProcessBuilder subProcessBuilder = beforeBpmnLink.nextSubProcess(sequenceFlowMapping(config, seq), subProcessId);
                    fillIterableProperty(config, targetNode, subProcessBuilder::iterable);

                    ElementPropertyUtil.getNodeProperty(targetNode,
                            BpmnElementProperties.TASK_STRICT_MODE).map(BooleanUtils::toBooleanObject).filter(b -> !b).ifPresent(b -> subProcessBuilder.notStrictMode());
                    ElementPropertyUtil.getNodeProperty(targetNode, BpmnElementProperties.TASK_TIMEOUT).map(s -> NumberUtils.toInt(s, -1)).filter(i -> i >= 0).ifPresent(subProcessBuilder::timeout);
                    nextBpmnLinkMap.put(targetNode, subProcessBuilder.build());
                    basicInStack.push(targetNode);
                } else {
                    throw ExceptionUtil.buildException(null, ExceptionEnum.CONFIGURATION_UNSUPPORTED_ELEMENT, GlobalUtil.format("There is an error in the bpmn file! fileName: {}", config.getConfigName()));
                }

                if (isBpmnSupportAggregation(targetNode)) {
                    comingCountMap.merge(targetNode, 1, Integer::sum);
                    if (Objects.equals(comingCountMap.get(targetNode), targetNode.getIncoming().size())) {
                        basicInStack.push(targetNode);
                    }
                } else {
                    AssertUtil.notTrue(circularDependencyCheck.contains(targetNode), ExceptionEnum.CONFIGURATION_FLOW_ERROR, "Duplicate calls between elements are not allowed! elementId: {}, elementName: {}", targetNode.getId(), targetNode.getName());
                    circularDependencyCheck.add(targetNode);
                }
            }
        }
    }

    private boolean isBpmnSupportAggregation(org.camunda.bpm.model.bpmn.instance.FlowElement element) {
        return element instanceof org.camunda.bpm.model.bpmn.instance.EndEvent
                || element instanceof org.camunda.bpm.model.bpmn.instance.ParallelGateway
                || element instanceof org.camunda.bpm.model.bpmn.instance.InclusiveGateway;
    }

    private ServiceTaskImpl getServiceTask(FlowNode flowNode, ConfigResource config) {
        ServiceTaskImpl serviceTaskImpl = new ServiceTaskImpl();
        serviceTaskImpl.setId(flowNode.getId());
        serviceTaskImpl.setName(flowNode.getName());
        AssertUtil.notBlank(serviceTaskImpl.getId(), ExceptionEnum.CONFIGURATION_ATTRIBUTES_REQUIRED, "The bpmn element id attribute cannot be empty! fileName: {}", config.getConfigName());
        ElementPropertyUtil.getNodeProperty(flowNode, BpmnElementProperties.SERVICE_TASK_TASK_COMPONENT).ifPresent(serviceTaskImpl::setTaskComponent);
        ElementPropertyUtil.getNodeProperty(flowNode, BpmnElementProperties.SERVICE_TASK_TASK_SERVICE).ifPresent(serviceTaskImpl::setTaskService);
        ElementPropertyUtil.getNodeProperty(flowNode, BpmnElementProperties.SERVICE_TASK_TASK_PROPERTY).ifPresent(serviceTaskImpl::setTaskProperty);
        ElementPropertyUtil.getNodeProperty(flowNode, BpmnElementProperties.SERVICE_TASK_CUSTOM_ROLE).flatMap(CustomRoleInfo::buildCustomRole).ifPresent(serviceTaskImpl::setCustomRoleInfo);
        ElementPropertyUtil.getNodeProperty(flowNode, BpmnElementProperties.TASK_ALLOW_ABSENT).map(BooleanUtils::toBooleanObject).ifPresent(serviceTaskImpl::setAllowAbsent);
        ElementPropertyUtil.getNodeProperty(flowNode, BpmnElementProperties.TASK_STRICT_MODE).map(BooleanUtils::toBooleanObject).ifPresent(serviceTaskImpl::setStrictMode);
        ElementPropertyUtil.getNodeProperty(flowNode, BpmnElementProperties.TASK_TIMEOUT).map(s -> NumberUtils.toInt(s, -1)).filter(i -> i >= 0).ifPresent(serviceTaskImpl::setTimeout);
        Pair<String, String> instructPair = ElementPropertyUtil.getNodeProperty(flowNode, BpmnElementProperties.SERVICE_TASK_TASK_INSTRUCT, true);
        if (StringUtils.isNotBlank(instructPair.getLeft())) {
            serviceTaskImpl.setTaskInstruct(Optional.of(instructPair.getLeft()).map(String::trim).orElse(null));
            serviceTaskImpl.setTaskInstructContent(instructPair.getRight());
        }
        fillIterableProperty(config, flowNode, serviceTaskImpl::mergeElementIterable);
        return serviceTaskImpl;
    }

    private void fillIterableProperty(ConfigResource config, FlowNode flowNode, Consumer<BasicElementIterable> setConsumer) {
        BasicElementIterable elementIterable = new BasicElementIterable();
        Optional<String> iteSourceProperty = ElementPropertyUtil.getNodeProperty(flowNode, BpmnElementProperties.ITERATE_SOURCE);
        if (iteSourceProperty.filter(s -> {
            if (ElementParserUtil.isValidDataExpression(s)) {
                return true;
            }
            LOGGER.warn("[{}] The set ite-source being iterated over is invalid. fileName: {}, calledElementId: {}", ExceptionEnum.BPMN_ATTRIBUTE_INVALID.getExceptionCode(), config.getConfigName(), flowNode.getId());
            return false;
        }).map(StringUtils::isNotBlank).orElse(false)) {
            elementIterable.setIteSource(iteSourceProperty.get());
        }
        ElementPropertyUtil.getNodeProperty(flowNode, BpmnElementProperties.ITERATE_ASYNC).map(BooleanUtils::toBoolean).ifPresent(elementIterable::setOpenAsync);
        ElementPropertyUtil.getNodeProperty(flowNode, BpmnElementProperties.ITERATE_STRATEGY).flatMap(IterateStrategyEnum::of).ifPresent(elementIterable::setIteStrategy);
        ElementPropertyUtil.getNodeProperty(flowNode, BpmnElementProperties.ITERATE_STRIDE).map(NumberUtils::toInt).ifPresent(elementIterable::setStride);
        setConsumer.accept(elementIterable);
    }

    private SequenceFlow sequenceFlowMapping(ConfigResource config, org.camunda.bpm.model.bpmn.instance.SequenceFlow sf) {
        SequenceFlowImpl sequenceFlow = new SequenceFlowImpl();
        sequenceFlow.setId(sf.getId());
        sequenceFlow.setName(sf.getName());
        AssertUtil.notBlank(sequenceFlow.getId(), ExceptionEnum.CONFIGURATION_ATTRIBUTES_REQUIRED, "The bpmn element id attribute cannot be empty! fileName: {}", config.getConfigName());
        if (sf.getConditionExpression() != null && StringUtils.isNotBlank(sf.getConditionExpression().getTextContent())) {
            SequenceFlowExpression sequenceFlowExpression = new SequenceFlowExpression(sf.getConditionExpression().getTextContent());
            sequenceFlowExpression.setId(sf.getConditionExpression().getId());
            sequenceFlowExpression.setName(sf.getConditionExpression().getTextContent());
            sequenceFlow.setExpression(sequenceFlowExpression);
        }
        return sequenceFlow;
    }
}