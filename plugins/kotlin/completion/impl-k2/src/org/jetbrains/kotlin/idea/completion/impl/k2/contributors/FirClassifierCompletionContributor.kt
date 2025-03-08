// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.kotlin.idea.completion.impl.k2.contributors

import com.intellij.codeInsight.lookup.*
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.symbols.*
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.idea.base.psi.replaced
import org.jetbrains.kotlin.idea.completion.KotlinFirCompletionParameters
import org.jetbrains.kotlin.idea.completion.contributors.helpers.CompletionSymbolOrigin
import org.jetbrains.kotlin.idea.completion.contributors.helpers.FirClassifierProvider.getAvailableClassifiers
import org.jetbrains.kotlin.idea.completion.contributors.helpers.FirClassifierProvider.getAvailableClassifiersFromIndex
import org.jetbrains.kotlin.idea.completion.contributors.helpers.KtSymbolWithOrigin
import org.jetbrains.kotlin.idea.completion.contributors.helpers.staticScope
import org.jetbrains.kotlin.idea.completion.impl.k2.LookupElementSink
import org.jetbrains.kotlin.idea.completion.lookups.ImportStrategy
import org.jetbrains.kotlin.idea.completion.lookups.factories.KotlinFirLookupElementFactory
import org.jetbrains.kotlin.idea.completion.lookups.factories.shortenCommand
import org.jetbrains.kotlin.idea.completion.reference
import org.jetbrains.kotlin.idea.completion.weighers.Weighers.applyWeighs
import org.jetbrains.kotlin.idea.completion.weighers.WeighingContext
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.idea.util.positionContext.KotlinNameReferencePositionContext
import org.jetbrains.kotlin.idea.util.positionContext.KotlinTypeNameReferencePositionContext
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.renderer.render
import kotlin.reflect.KClass

internal open class FirClassifierCompletionContributor(
    parameters: KotlinFirCompletionParameters,
    sink: LookupElementSink,
    priority: Int = 0,
) : FirCompletionContributorBase<KotlinNameReferencePositionContext>(parameters, sink, priority) {

    private val psiFactory = KtPsiFactory(project)

    context(KaSession)
    protected open fun filterClassifiers(classifierSymbol: KaClassifierSymbol): Boolean = true

    context(KaSession)
    protected open fun getImportingStrategy(classifierSymbol: KaClassifierSymbol): ImportStrategy =
        importStrategyDetector.detectImportStrategyForClassifierSymbol(classifierSymbol)

    context(KaSession)
    override fun complete(
        positionContext: KotlinNameReferencePositionContext,
        weighingContext: WeighingContext,
    ) {
        when (val receiver = positionContext.explicitReceiver) {
            null -> completeWithoutReceiver(positionContext, weighingContext)

            else -> {
                receiver.reference()?.let {
                    completeWithReceiver(positionContext, weighingContext, it)
                } ?: emptySequence()
            }
        }.forEach(sink::addElement)
    }

    context(KaSession)
    private fun completeWithReceiver(
        positionContext: KotlinNameReferencePositionContext,
        context: WeighingContext,
        reference: KtReference,
    ): Sequence<LookupElementBuilder> = reference
        .resolveToSymbols()
        .asSequence()
        .mapNotNull { it.staticScope }
        .flatMap { scopeWithKind ->
            scopeWithKind.scope
                .classifiers(scopeNameFilter)
                .filter { filterClassifiers(it) }
                .filter { visibilityChecker.isVisible(it, positionContext) }
                .mapNotNull { classifierSymbol ->
                    createClassifierLookupElement(
                        classifierSymbol = classifierSymbol,
                        positionContext = positionContext,
                        importingStrategy = ImportStrategy.DoNothing,
                    )?.applyWeighs(
                        context = context,
                        symbolWithOrigin = KtSymbolWithOrigin(classifierSymbol, CompletionSymbolOrigin.Scope(scopeWithKind.kind))
                    )
                }
        }

    context(KaSession)
    private fun completeWithoutReceiver(
        positionContext: KotlinNameReferencePositionContext,
        context: WeighingContext,
    ): Sequence<LookupElementBuilder> {
        val availableFromScope = mutableSetOf<KaClassifierSymbol>()
        val scopeClassifiers = context.scopeContext!!
            .scopes
            .asSequence()
            .flatMap { it.getAvailableClassifiers(positionContext, scopeNameFilter, visibilityChecker) }
            .filter { filterClassifiers(it.symbol) }
            .mapNotNull { symbolWithScopeKind ->
                val classifierSymbol = symbolWithScopeKind.symbol
                availableFromScope += classifierSymbol

                createClassifierLookupElement(
                    classifierSymbol = classifierSymbol,
                    positionContext = positionContext,
                    importingStrategy = getImportingStrategy(classifierSymbol),
                )?.applyWeighs(
                    context = context,
                    symbolWithOrigin = KtSymbolWithOrigin(classifierSymbol, CompletionSymbolOrigin.Scope(symbolWithScopeKind.scopeKind)),
                )
            }

        val indexClassifiers = if (prefixMatcher.prefix.isNotEmpty()) {
            getAvailableClassifiersFromIndex(
                positionContext = positionContext,
                parameters = parameters,
                symbolProvider = symbolFromIndexProvider,
                scopeNameFilter = scopeNameFilter,
                visibilityChecker = visibilityChecker,
            ).filter { it !in availableFromScope && filterClassifiers(it) }
                .mapNotNull { classifierSymbol ->
                    createClassifierLookupElement(
                        classifierSymbol = classifierSymbol,
                        positionContext = positionContext,
                        importingStrategy = getImportingStrategy(classifierSymbol),
                    )?.applyWeighs(
                        context = context,
                        symbolWithOrigin = KtSymbolWithOrigin(classifierSymbol, CompletionSymbolOrigin.Index),
                    )
                }
        } else {
            emptySequence()
        }

        return scopeClassifiers +
                indexClassifiers
    }

    context(KaSession)
    private fun createClassifierLookupElement(
        classifierSymbol: KaClassifierSymbol,
        positionContext: KotlinNameReferencePositionContext,
        importingStrategy: ImportStrategy,
    ): LookupElementBuilder? {
        val builder = KotlinFirLookupElementFactory.createClassifierLookupElement(classifierSymbol, importingStrategy)
            ?: return null

        return when (importingStrategy) {
            is ImportStrategy.InsertFqNameAndShorten -> builder.withExpensiveRenderer(object : LookupElementRenderer<LookupElement>() {

                /**
                 * @see [com.intellij.codeInsight.lookup.impl.AsyncRendering.scheduleRendering]
                 * todo investigate refactoring to [SuspendingLookupElementRenderer]
                 */
                override fun renderElement(
                    lookupElement: LookupElement,
                    presentation: LookupElementPresentation,
                ) {
                    lookupElement.renderElement(presentation)

                    // avoiding PsiInvalidElementAccessException in completionFile
                    if (!parameters.position.isValid) return
                    val file = parameters.completionFile
                        .copy() as KtFile
                    val element = file.findElementAt(parameters.offset)
                        ?: return

                    val factory = when (positionContext) {
                        is KotlinTypeNameReferencePositionContext -> ({ type: String -> psiFactory.createType(type) }).asFactory()
                        else -> (psiFactory::createExpression).asFactory()
                    }

                    val parent = PsiTreeUtil.getParentOfType(
                        /* element = */ element,
                        /* aClass = */ factory.parentClass.java,
                        /* strict = */ true,
                    ) ?: return

                    val useSiteElement = factory(fqName = importingStrategy.fqName)
                        .let { parent.replaced<KtElement>(it) }

                    lookupElement.shortenCommand = analyze(useSiteElement) {
                        collectPossibleReferenceShortenings(
                            file = file,
                            selection = useSiteElement.textRange,
                        )
                    }
                }
            })

            else -> builder
        }
    }
}

internal class FirAnnotationCompletionContributor(
    parameters: KotlinFirCompletionParameters,
    sink: LookupElementSink,
    priority: Int = 0,
) : FirClassifierCompletionContributor(parameters, sink, priority) {

    context(KaSession)
    override fun filterClassifiers(classifierSymbol: KaClassifierSymbol): Boolean = when (classifierSymbol) {
        is KaAnonymousObjectSymbol -> false
        is KaTypeParameterSymbol -> false
        is KaNamedClassSymbol -> when (classifierSymbol.classKind) {
            KaClassKind.ANNOTATION_CLASS -> true
            KaClassKind.ENUM_CLASS -> false
            KaClassKind.ANONYMOUS_OBJECT -> false
            KaClassKind.CLASS, KaClassKind.OBJECT, KaClassKind.COMPANION_OBJECT, KaClassKind.INTERFACE -> {
                classifierSymbol.staticDeclaredMemberScope.classifiers.any { filterClassifiers(it) }
            }
        }

        is KaTypeAliasSymbol -> {
            val expendedClass = (classifierSymbol.expandedType as? KaClassType)?.symbol
            expendedClass?.let { filterClassifiers(it) } == true
        }
    }
}

internal class FirClassifierReferenceCompletionContributor(
    parameters: KotlinFirCompletionParameters,
    sink: LookupElementSink,
    priority: Int
) : FirClassifierCompletionContributor(parameters, sink, priority) {

    context(KaSession)
    override fun getImportingStrategy(classifierSymbol: KaClassifierSymbol): ImportStrategy = when (classifierSymbol) {
        is KaTypeParameterSymbol -> ImportStrategy.DoNothing
        is KaClassLikeSymbol -> {
            classifierSymbol.classId?.let { ImportStrategy.AddImport(it.asSingleFqName()) } ?: ImportStrategy.DoNothing
        }
    }
}

private inline fun <reified R : KtElement> ((String) -> R).asFactory(
): MethodBasedElementFactory<R> = object : MethodBasedElementFactory<R>() {

    override val parentClass: KClass<R>
        get() = R::class

    override fun invoke(text: String): R =
        this@asFactory(text)
}

private abstract class MethodBasedElementFactory<R : KtElement> {

    abstract val parentClass: KClass<R>

    abstract operator fun invoke(text: String): R
}

private operator fun <R : KtElement> MethodBasedElementFactory<R>.invoke(fqName: FqName): R =
    this(text = fqName.render())
