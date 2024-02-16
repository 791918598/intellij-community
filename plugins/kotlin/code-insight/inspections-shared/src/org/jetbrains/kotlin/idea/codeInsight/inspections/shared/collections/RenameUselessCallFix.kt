// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.codeInsight.inspections.shared.collections

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.project.Project
import org.jetbrains.kotlin.idea.base.psi.replaced
import org.jetbrains.kotlin.idea.base.resources.KotlinBundle
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtPrefixExpression
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtReturnExpression
import org.jetbrains.kotlin.psi.createExpressionByPattern
import org.jetbrains.kotlin.psi.psiUtil.forEachDescendantOfType
import org.jetbrains.kotlin.utils.addToStdlib.safeAs

class RenameUselessCallFix(private val newName: String, private val invert: Boolean = false) : LocalQuickFix {
    override fun getName() = KotlinBundle.message("rename.useless.call.fix.text", newName)

    override fun getFamilyName() = name

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val qualifiedExpression = descriptor.psiElement as? KtQualifiedExpression ?: return
        val psiFactory = KtPsiFactory(project)
        val selectorCallExpression = qualifiedExpression.selectorExpression as? KtCallExpression
        val calleeExpression = selectorCallExpression?.calleeExpression ?: return
        calleeExpression.replaced(psiFactory.createExpression(newName))
        selectorCallExpression.renameGivenReturnLabels(psiFactory, calleeExpression.text, newName)
        if (invert) qualifiedExpression.invert()
    }

    private fun KtCallExpression.renameGivenReturnLabels(factory: KtPsiFactory, labelName: String, newName: String) {
        val lambdaExpression = lambdaArguments.firstOrNull()?.getLambdaExpression() ?: return
        val bodyExpression = lambdaExpression.bodyExpression ?: return

        bodyExpression.forEachDescendantOfType<KtReturnExpression> {
            if (it.getLabelName() != labelName) return@forEachDescendantOfType

            it.replaced(
                factory.createExpressionByPattern(
                    "return@$0 $1",
                    newName,
                    it.returnedExpression ?: ""
                )
            )
        }
    }

    private fun KtQualifiedExpression.invert() {
        val parent = parent.safeAs<KtPrefixExpression>() ?: return
        val baseExpression = parent.baseExpression ?: return
        parent.replace(baseExpression)
    }
}
