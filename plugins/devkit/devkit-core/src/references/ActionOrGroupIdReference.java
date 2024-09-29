// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.idea.devkit.references;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.pom.references.PomService;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementResolveResult;
import com.intellij.psi.PsiPolyVariantReferenceBase;
import com.intellij.psi.ResolveResult;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.ProjectScope;
import com.intellij.util.CommonProcessors;
import com.intellij.util.containers.JBIterable;
import com.intellij.util.xml.DomTarget;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.idea.devkit.dom.ActionOrGroup;
import org.jetbrains.idea.devkit.dom.OverrideText;
import org.jetbrains.idea.devkit.dom.index.IdeaPluginRegistrationIndex;

import java.util.List;
import java.util.Objects;

final class ActionOrGroupIdReference extends PsiPolyVariantReferenceBase<PsiElement> {

  private final String myId;
  private final boolean myIsAction;

  ActionOrGroupIdReference(@NotNull PsiElement element, String id, String prefix) {
    super(element, TextRange.allOf(id).shiftRight(prefix.length()));
    myIsAction = prefix.equals(MessageBundleReferenceContributor.ACTION);
    myId = id;
  }

  @NotNull
  @Override
  public ResolveResult @NotNull [] multiResolve(boolean incompleteCode) {
    Project project = getElement().getProject();

    final GlobalSearchScope scope = ProjectScope.getContentScope(project);

    CommonProcessors.CollectUniquesProcessor<ActionOrGroup> processor = new CommonProcessors.CollectUniquesProcessor<>();
    if (myIsAction) {
      IdeaPluginRegistrationIndex.processAction(project, myId, scope, processor);
    }
    else {
      IdeaPluginRegistrationIndex.processGroup(project, myId, scope, processor);
    }

    // action|group.ActionId.<override-text@place>.text
    if (processor.getResults().isEmpty()) {
      String place = StringUtil.substringAfterLast(myId, ".");
      if (StringUtil.isEmpty(place)) return ResolveResult.EMPTY_ARRAY;

      String idWithoutPlaceSuffix = StringUtil.substringBeforeLast(myId, ".");

      if (myIsAction) {
        IdeaPluginRegistrationIndex.processAction(project, idWithoutPlaceSuffix, scope, processor);
      }
      else {
        IdeaPluginRegistrationIndex.processGroup(project, idWithoutPlaceSuffix, scope, processor);
      }

      for (ActionOrGroup result : processor.getResults()) {
        for (OverrideText overrideText : result.getOverrideTexts()) {
          if (place.equals(overrideText.getPlace().getStringValue())) {
            final DomTarget overrideTarget = DomTarget.getTarget(overrideText, overrideText.getPlace());
            assert overrideTarget != null;
            return PsiElementResolveResult.createResults(PomService.convertToPsi(overrideTarget));
          }
        }
      }
      return ResolveResult.EMPTY_ARRAY;
    }

    final List<PsiElement> psiElements =
      JBIterable.from(processor.getResults())
        .map(actionOrGroup -> {
          final DomTarget target = DomTarget.getTarget(actionOrGroup);
          return target == null ? null : PomService.convertToPsi(project, target);
        }).filter(Objects::nonNull).toList();
    return PsiElementResolveResult.createResults(psiElements);
  }
}
