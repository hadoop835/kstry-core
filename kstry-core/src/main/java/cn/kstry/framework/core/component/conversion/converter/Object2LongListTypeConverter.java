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
package cn.kstry.framework.core.component.conversion.converter;

import cn.kstry.framework.core.component.conversion.TypeConverter;
import cn.kstry.framework.core.constant.TypeConverterNames;
import cn.kstry.framework.core.util.GlobalUtil;
import com.alibaba.fastjson.JSON;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 默认不启用
 */
@SuppressWarnings("rawtypes")
public class Object2LongListTypeConverter implements TypeConverter<Object, List> {

    @Override
    public boolean match(Object source, Class<?> needType) {
        return false;
    }

    @Override
    public List<?> doConvert(Object source, @Nullable Class<?> needType) {
        if (source instanceof String) {
            return JSON.parseArray((String) source, Long.class);
        }
        if (source instanceof Iterable || source.getClass().isArray()) {
            return JSON.parseArray(JSON.toJSONString(source), Long.class);
        }
        throw new RuntimeException(GlobalUtil.format("Unsupported type conversions. type[{}]", source.getClass()));
    }

    @Override
    public Class<Object> getSourceType() {
        return Object.class;
    }

    @Override
    public Class<List> getTargetType() {
        return List.class;
    }

    @Override
    public String getConvertName() {
        return TypeConverterNames.OBJECT_TO_LONG_LIST;
    }
}
