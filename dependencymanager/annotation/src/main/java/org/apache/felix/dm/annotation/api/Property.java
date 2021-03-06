/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.felix.dm.annotation.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation used to describe a property key-value pair. It is used when
 * declaring {@link Component#properties()} attribute.
 * This annotation only support properties which type is either a
 * String, or a String array.
 * If you need to specify some properties with types other than Strings,
 * then use the {@link Start} annotation, which allows to annotate a <code>start</code>
 * method which may optionally return a Map of any service property types.
 * 
 * @author <a href="mailto:dev@felix.apache.org">Felix Project Team</a>
 */
@Retention(RetentionPolicy.CLASS)
@Target( { ElementType.ANNOTATION_TYPE })
public @interface Property
{
    /**
     * Returns the property name.
     * @return this property name
     */
    String name();

    /**
     * Returns the property value
     * @return this property value
     */
    String value() default "";
    
    /**
     * Returns the property value as a String array.
     * @return this property value as a String array
     */
    String[] values() default {};
}
