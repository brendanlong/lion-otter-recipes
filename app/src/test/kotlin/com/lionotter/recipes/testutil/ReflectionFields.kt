package com.lionotter.recipes.testutil

import java.lang.reflect.Field
import java.lang.reflect.Modifier

/** The backing fields of a Kotlin class's properties (constructor properties for data classes). */
fun propertyFields(type: Class<*>): List<Field> =
    type.declaredFields
        .filterNot { Modifier.isStatic(it.modifiers) || it.isSynthetic || '$' in it.name }
        .onEach { it.isAccessible = true }

fun propertyNames(type: Class<*>): Set<String> = propertyFields(type).map { it.name }.toSet()
