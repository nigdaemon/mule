/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.module.extension.internal.loader.java.type;

/**
 * A generic contract for any kind of component that could contain a return type
 *
 * @since 4.0
 */
interface WithReturnType {

  /**
   * @return the return type {@link Class} of the implementer component
   */
  Class<?> getReturnType();
}
