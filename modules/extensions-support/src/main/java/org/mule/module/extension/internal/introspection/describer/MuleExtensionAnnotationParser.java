/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.module.extension.internal.introspection.describer;

import static org.mule.module.extension.internal.util.IntrospectionUtils.getFieldDataType;
import static org.mule.module.extension.internal.util.MuleExtensionUtils.getDefaultValue;
import static org.mule.util.Preconditions.checkState;
import org.mule.api.MuleEvent;
import org.mule.api.temporary.MuleMessage;
import org.mule.extension.api.annotation.Alias;
import org.mule.extension.api.annotation.Extension;
import org.mule.extension.api.annotation.ParameterGroup;
import org.mule.extension.api.annotation.RestrictedTo;
import org.mule.extension.api.annotation.param.Connection;
import org.mule.extension.api.annotation.param.Optional;
import org.mule.extension.api.annotation.param.UseConfig;
import org.mule.extension.api.annotation.param.display.Password;
import org.mule.extension.api.annotation.param.display.Placement;
import org.mule.extension.api.annotation.param.display.Text;
import org.mule.extension.api.introspection.DataType;
import org.mule.extension.api.introspection.EnrichableModel;
import org.mule.extension.api.introspection.declaration.fluent.BaseDeclaration;
import org.mule.extension.api.introspection.declaration.fluent.HasModelProperties;
import org.mule.extension.api.introspection.property.display.ImmutablePasswordModelProperty;
import org.mule.extension.api.introspection.property.display.ImmutablePlacementModelProperty;
import org.mule.extension.api.introspection.property.display.ImmutableTextModelProperty;
import org.mule.extension.api.introspection.property.display.PasswordModelProperty;
import org.mule.extension.api.introspection.property.display.PlacementModelProperty;
import org.mule.extension.api.introspection.property.display.TextModelProperty;
import org.mule.module.extension.internal.model.property.DeclaringMemberModelProperty;
import org.mule.module.extension.internal.util.IntrospectionUtils;
import org.mule.util.ClassUtils;
import org.mule.util.CollectionUtils;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang.StringUtils;

/**
 * Utilities for reading annotations as a mean to describe extensions
 *
 * @since 3.7.0
 */
public final class MuleExtensionAnnotationParser
{

    private static final Set<Class<?>> IMPLICIT_ARGUMENT_TYPES = ImmutableSet.<Class<?>>builder()
            .add(MuleEvent.class)
            .add(org.mule.api.temporary.MuleMessage.class)
            .add(MuleMessage.class)
            .build();

    static String getAliasName(Field field)
    {
        return getAliasName(field.getName(), field.getAnnotation(Alias.class));
    }

    static String getAliasName(String defaultName, Alias aliasAnnotation)
    {
        String alias = aliasAnnotation != null ? aliasAnnotation.value() : null;
        return StringUtils.isEmpty(alias) ? defaultName : alias;
    }

    public static String getMemberName(BaseDeclaration<?> declaration, String defaultName)
    {
        DeclaringMemberModelProperty declaringMemberModelProperty = declaration.getModelProperty(DeclaringMemberModelProperty.KEY);
        return declaringMemberModelProperty != null ? declaringMemberModelProperty.getDeclaringField().getName() : defaultName;
    }

    public static String getMemberName(EnrichableModel enrichableModel, String defaultName)
    {
        DeclaringMemberModelProperty declaringMemberModelProperty = enrichableModel.getModelProperty(DeclaringMemberModelProperty.KEY);
        return declaringMemberModelProperty != null ? declaringMemberModelProperty.getDeclaringField().getName() : defaultName;
    }

    public static Extension getExtension(Class<?> extensionType)
    {
        Extension extension = extensionType.getAnnotation(Extension.class);
        checkState(extension != null, String.format("%s is not a Mule extension since it's not annotated with %s",
                                                    extensionType.getName(), Extension.class.getName()));

        return extension;
    }

    static List<ParsedParameter> parseParameters(Method method)
    {
        List<String> paramNames = getParamNames(method);

        if (CollectionUtils.isEmpty(paramNames))
        {
            return ImmutableList.of();
        }

        DataType[] parameterTypes = IntrospectionUtils.getMethodArgumentTypes(method);
        Annotation[][] parameterAnnotations = method.getParameterAnnotations();

        List<ParsedParameter> parsedParameters = new LinkedList<>();


        for (int i = 0; i < paramNames.size(); i++)
        {
            Map<Class<? extends Annotation>, Annotation> annotations = toMap(parameterAnnotations[i]);

            if (annotations.containsKey(ParameterGroup.class))
            {
                parseGroupParameters(parameterTypes[i], parsedParameters);
            }
            else
            {
                ParsedParameter parsedParameter = doParseParameter(paramNames.get(i), parameterTypes[i], annotations);
                parsedParameters.add(parsedParameter);
            }
        }

        return parsedParameters;
    }

    private static void parseGroupParameters(DataType parameterType, List<ParsedParameter> parsedParameters)
    {
        for (Field field : IntrospectionUtils.getParameterFields(parameterType.getRawType()))
        {
            if (field.getAnnotation(org.mule.extension.api.annotation.ParameterGroup.class) != null)
            {
                parseGroupParameters(getFieldDataType(field), parsedParameters);
            }
            else
            {
                ParsedParameter parsedParameter = doParseParameter(field.getName(), getFieldDataType(field), toMap(field.getAnnotations()));
                if (parsedParameter != null)
                {
                    parsedParameters.add(parsedParameter);
                }
            }
        }
    }

    private static ParsedParameter doParseParameter(String paramName, DataType dataType, Map<Class<? extends Annotation>, Annotation> annotations)
    {
        ParsedParameter parameter = new ParsedParameter(annotations);
        parameter.setAdvertised(shouldAdvertise(dataType, annotations));

        parameter.setName(getAliasName(paramName, (Alias) annotations.get(Alias.class)));
        parameter.setType(dataType);

        Optional optional = (Optional) annotations.get(Optional.class);
        if (optional != null)
        {
            parameter.setRequired(false);
            parameter.setDefaultValue(getDefaultValue(optional));
        }
        else
        {
            parameter.setRequired(true);
        }

        RestrictedTo typeRestriction = (RestrictedTo) annotations.get(RestrictedTo.class);
        if (typeRestriction != null)
        {
            parameter.setTypeRestriction(typeRestriction.value());
        }
        return parameter;
    }

    private static boolean shouldAdvertise(DataType parameterType, Map<Class<? extends Annotation>, Annotation> annotations)
    {
        return !(IMPLICIT_ARGUMENT_TYPES.contains(parameterType.getRawType()) ||
                 annotations.containsKey(UseConfig.class) ||
                 annotations.containsKey(Connection.class));
    }

    public static List<String> getParamNames(Method method)
    {
        ImmutableList.Builder<String> paramNames = ImmutableList.builder();
        for (java.lang.reflect.Parameter parameter : method.getParameters())
        {
            paramNames.add(parameter.getName());
        }

        return paramNames.build();
    }

    public static Map<Class<? extends Annotation>, Annotation> toMap(Annotation[] annotations)
    {

        Map<Class<? extends Annotation>, Annotation> map = new HashMap<>();

        for (Annotation annotation : annotations)
        {
            map.put(ClassUtils.resolveAnnotationClass(annotation), annotation);
        }

        return map;
    }

    public static void parseDisplayAnnotations(AnnotatedElement annotatedElement, HasModelProperties parameter)
    {
        Password passwordAnnotation = annotatedElement.getAnnotation(Password.class);
        if (passwordAnnotation != null)
        {
            parameter.withModelProperty(PasswordModelProperty.KEY, new ImmutablePasswordModelProperty());
        }
        Text textAnnotation = annotatedElement.getAnnotation(Text.class);
        if (textAnnotation != null)
        {
            parameter.withModelProperty(TextModelProperty.KEY, new ImmutableTextModelProperty());
        }
        Placement placementAnnotation = annotatedElement.getAnnotation(Placement.class);
        if (placementAnnotation != null)
        {
            parameter.withModelProperty(PlacementModelProperty.KEY, new ImmutablePlacementModelProperty(placementAnnotation.order(), placementAnnotation.group(), placementAnnotation.tab()));
        }
    }

}