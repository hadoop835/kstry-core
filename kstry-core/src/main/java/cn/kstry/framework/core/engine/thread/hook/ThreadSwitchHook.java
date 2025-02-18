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
package cn.kstry.framework.core.engine.thread.hook;

import cn.kstry.framework.core.bus.ScopeDataQuery;
import org.springframework.core.Ordered;

/**
 * 线程切换时的钩子
 *
 * @param <T>
 */
public interface ThreadSwitchHook<T> extends Ordered {

    /**
     * 获取线程切换前的数据
     *
     * @param scopeDataQuery 域数据查询
     * @return 线程切换前的数据
     */
    T getPreviousData(ScopeDataQuery scopeDataQuery);

    /**
     * 将线程切换前的数据应用到新的线程
     *
     * @param data 线程切换前的数据
     * @param scopeDataQuery 域数据查询
     */
    void usePreviousData(T data, ScopeDataQuery scopeDataQuery);

    default int getOrder() {
        return LOWEST_PRECEDENCE;
    }
}
