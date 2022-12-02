/*
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.common.serialization.unlinked

import org.jetbrains.kotlin.backend.common.serialization.unlinked.PartialLinkageUtils.Module.DescriptorBased.Companion.moduleDescriptor
import org.jetbrains.kotlin.backend.common.serialization.unlinked.PartialLinkageUtils.Module.IrBased.Companion.irModule
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.ir.UNDEFINED_COLUMN_NUMBER
import org.jetbrains.kotlin.ir.UNDEFINED_LINE_NUMBER
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.lazy.IrLazyDeclarationBase
import org.jetbrains.kotlin.ir.util.IdSignature
import org.jetbrains.kotlin.ir.util.IrMessageLogger.Location
import org.jetbrains.kotlin.ir.util.fileOrNull
import org.jetbrains.kotlin.ir.util.nameForIrSerialization
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.resolve.descriptorUtil.module

internal object PartialLinkageUtils {
    val UNKNOWN_NAME = Name.identifier("<unknown name>")

    fun IdSignature.guessName(nameSegmentsToPickUp: Int): String? = when (this) {
        is IdSignature.CommonSignature -> if (nameSegmentsToPickUp == 1)
            shortName
        else
            nameSegments.takeLast(nameSegmentsToPickUp).joinToString(".")

        is IdSignature.CompositeSignature -> inner.guessName(nameSegmentsToPickUp)
        is IdSignature.AccessorSignature -> accessorSignature.guessName(nameSegmentsToPickUp)

        else -> null
    }

    /** For fast check if a declaration is in the module. Also for visibility check. */
    internal sealed interface Module {
        val name: String

        operator fun contains(fragment: IrModuleFragment): Boolean
        operator fun contains(declaration: IrDeclaration): Boolean

        class IrBased(private val module: IrModuleFragment) : Module {
            override val name get() = module.name.asString()
            override fun contains(fragment: IrModuleFragment) = fragment == module
            override fun contains(declaration: IrDeclaration) = declaration.irModule == module

            companion object {
                inline val IrDeclaration.irModule: IrModuleFragment? get() = fileOrNull?.module
            }
        }

        class DescriptorBased(private val module: ModuleDescriptor) : Module {
            override val name get() = module.name.asString()
            override fun contains(fragment: IrModuleFragment) = fragment.descriptor == module
            override fun contains(declaration: IrDeclaration) = declaration.moduleDescriptor == module

            companion object {
                inline val IrDeclaration.moduleDescriptor: ModuleDescriptor? get() = (this as? IrLazyDeclarationBase)?.descriptor?.module
            }
        }

        object MissingDeclarations : Module {
            override val name get() = "<missing declarations>"
            override fun contains(fragment: IrModuleFragment) = false
            override fun contains(declaration: IrDeclaration) = false
        }

        companion object {
            fun determineFor(declaration: IrDeclaration): Module {
                return if (declaration.origin == PartiallyLinkedDeclarationOrigin.MISSING_DECLARATION)
                    MissingDeclarations
                else
                    declaration.irModule?.let(::IrBased)
                        ?: declaration.moduleDescriptor?.let(::DescriptorBased)
                        ?: error("Can't determine module for $declaration, ${declaration.nameForIrSerialization}")
            }
        }
    }

    /** For visibility check. */
    sealed interface File {
        val module: Module
        fun computeLocationForOffset(offset: Int): Location

        class IrBased(private val file: IrFile) : File {
            override val module = Module.IrBased(file.module)

            override fun computeLocationForOffset(offset: Int): Location {
                val lineNumber = if (offset == UNDEFINED_OFFSET) UNDEFINED_LINE_NUMBER else file.fileEntry.getLineNumber(offset)
                val columnNumber = if (offset == UNDEFINED_OFFSET) UNDEFINED_COLUMN_NUMBER else file.fileEntry.getColumnNumber(offset)

                // TODO: should module name still be added here?
                return Location("${module.name} @ ${file.fileEntry.name}", lineNumber, columnNumber)
            }
        }

        object MissingDeclarations : File {
            private val LOCATION = Location(module.name, UNDEFINED_LINE_NUMBER, UNDEFINED_COLUMN_NUMBER)

            override val module get() = Module.MissingDeclarations
            override fun computeLocationForOffset(offset: Int) = LOCATION
        }

        companion object {
            fun determineFor(declaration: IrDeclaration): File {
                return if (declaration.origin == PartiallyLinkedDeclarationOrigin.MISSING_DECLARATION)
                    MissingDeclarations
                else
                    declaration.fileOrNull?.let(::IrBased)
                        ?: error("Can't determine file for $declaration, ${declaration.nameForIrSerialization}")
            }
        }
    }
}

/** An optimization to avoid computing file for every visited declaration */
internal abstract class FileAwareIrElementTransformerVoid(startingFile: PartialLinkageUtils.File?) : IrElementTransformerVoid() {
    private var _currentFile: PartialLinkageUtils.File? = startingFile
    protected val currentFile: PartialLinkageUtils.File get() = _currentFile ?: error("No information about current file")

    override fun visitFile(declaration: IrFile): IrFile {
        _currentFile = PartialLinkageUtils.File.IrBased(declaration)
        return try {
            super.visitFile(declaration)
        } finally {
            _currentFile = null
        }
    }
}
