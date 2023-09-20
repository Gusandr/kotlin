/*
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */
package kotlin.js

internal fun getPlatformArguments() = when {
    js("typeof process === 'object' && Array.isArray(process.argv)") -> js("process.argv")
    js("typeof Deno === 'object'") -> js("Deno.args")
    js("typeof Bun === 'object'") -> js("Bun.argv")
    else -> emptyArray<String>()
}
