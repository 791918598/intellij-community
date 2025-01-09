// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.java.codeserver.highlighting.errors;

import com.intellij.codeInsight.AnnotationTargetUtil;
import com.intellij.core.JavaPsiBundle;
import com.intellij.java.codeserver.highlighting.JavaCompilationErrorBundle;
import com.intellij.java.codeserver.highlighting.errors.JavaErrorKind.Parameterized;
import com.intellij.java.codeserver.highlighting.errors.JavaErrorKind.Simple;
import com.intellij.openapi.projectRoots.JavaSdkVersion;
import com.intellij.pom.java.JavaFeature;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiTypesUtil;
import com.intellij.psi.util.PsiUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.PropertyKey;

import java.util.List;
import java.util.stream.Collectors;

import static com.intellij.java.codeserver.highlighting.JavaCompilationErrorBundle.message;
import static com.intellij.java.codeserver.highlighting.errors.JavaErrorFormatUtil.formatClass;
import static com.intellij.java.codeserver.highlighting.errors.JavaErrorFormatUtil.formatMethod;
import static java.util.Objects.requireNonNull;
import static java.util.Objects.requireNonNullElse;

/**
 * All possible Java error kinds
 */
public final class JavaErrorKinds {
  private JavaErrorKinds() {}

  public static final Parameterized<PsiElement, @NotNull JavaFeature> UNSUPPORTED_FEATURE =
    parameterized(PsiElement.class, JavaFeature.class, "insufficient.language.level")
      .withRawDescription((element, feature) -> {
        String name = feature.getFeatureName();
        String version = JavaSdkVersion.fromLanguageLevel(PsiUtil.getLanguageLevel(element)).getDescription();
        return message("insufficient.language.level", name, version);
      });

  public static final Simple<PsiAnnotation> ANNOTATION_NOT_ALLOWED_HERE = error("annotation.not.allowed.here");
  public static final Simple<PsiPackageStatement> ANNOTATION_NOT_ALLOWED_ON_PACKAGE =
    error(PsiPackageStatement.class, "annotation.not.allowed.on.package")
      .withAnchor(statement -> requireNonNull(statement.getAnnotationList()));
  public static final Simple<PsiReferenceList> ANNOTATION_MEMBER_THROWS_NOT_ALLOWED =
    error(PsiReferenceList.class, "annotation.member.may.not.have.throws.list").withAnchor(list -> requireNonNull(list.getFirstChild()));
  public static final Simple<PsiReferenceList> ANNOTATION_NOT_ALLOWED_EXTENDS =
    error(PsiReferenceList.class, "annotation.may.not.have.extends.list").withAnchor(list -> requireNonNull(list.getFirstChild()));
  public static final Simple<PsiAnnotation> ANNOTATION_NOT_ALLOWED_VAR = error("annotation.not.allowed.var");
  public static final Simple<PsiAnnotation> ANNOTATION_NOT_ALLOWED_VOID = error("annotation.not.allowed.void");
  public static final Simple<PsiAnnotation> ANNOTATION_NOT_ALLOWED_CLASS = error("annotation.not.allowed.class");
  public static final Simple<PsiAnnotation> ANNOTATION_NOT_ALLOWED_REF = error("annotation.not.allowed.ref");
  public static final Simple<PsiAnnotation> ANNOTATION_NOT_ALLOWED_STATIC = error("annotation.not.allowed.static");
  public static final Simple<PsiJavaCodeReferenceElement> ANNOTATION_TYPE_EXPECTED = error("annotation.type.expected");
  public static final Simple<PsiReferenceExpression> ANNOTATION_REPEATED_TARGET = error("annotation.repeated.target");
  public static final Simple<PsiNameValuePair> ANNOTATION_ATTRIBUTE_ANNOTATION_NAME_IS_MISSING =
    error("annotation.attribute.annotation.name.is.missing");
  public static final Simple<PsiAnnotationMemberValue> ANNOTATION_ATTRIBUTE_NON_CLASS_LITERAL =
    error("annotation.attribute.non.class.literal");
  public static final Simple<PsiExpression> ANNOTATION_ATTRIBUTE_NON_ENUM_CONSTANT = error("annotation.attribute.non.enum.constant");
  public static final Simple<PsiExpression> ANNOTATION_ATTRIBUTE_NON_CONSTANT = error("annotation.attribute.non.constant");
  public static final Simple<PsiTypeElement> ANNOTATION_CYCLIC_TYPE = error("annotation.cyclic.element.type");
  public static final Parameterized<PsiMethod, PsiMethod> ANNOTATION_MEMBER_CLASH =
    error(PsiMethod.class, "annotation.member.clash")
      .withAnchor(curMethod -> requireNonNull(curMethod.getNameIdentifier()))
      .<PsiMethod>withContext()
      .withRawDescription((curMethod, clashMethod) -> {
        PsiClass containingClass = requireNonNull(clashMethod.getContainingClass());
        return message("annotation.member.clash", formatMethod(clashMethod), formatClass(containingClass));
      });
  public static final Parameterized<PsiTypeElement, PsiType> ANNOTATION_METHOD_INVALID_TYPE =
    parameterized(PsiTypeElement.class, PsiType.class, "annotation.member.invalid.type")
      .withRawDescription((element, type) ->
                            message("annotation.member.invalid.type", type == null ? null : type.getPresentableText()));
  public static final Parameterized<PsiAnnotationMemberValue, AnnotationValueErrorContext> ANNOTATION_ATTRIBUTE_INCOMPATIBLE_TYPE =
    parameterized(PsiAnnotationMemberValue.class, AnnotationValueErrorContext.class, 
                  "annotation.attribute.incompatible.type").withRawDescription((value, context) -> {
      String text = value instanceof PsiAnnotation annotation ? requireNonNull(annotation.getNameReferenceElement()).getText() :
                    PsiTypesUtil.removeExternalAnnotations(requireNonNull(((PsiExpression)value).getType())).getInternalCanonicalText();
      return message("annotation.attribute.incompatible.type", context.typeText(), text);
    });
  public static final Parameterized<PsiArrayInitializerMemberValue, AnnotationValueErrorContext> ANNOTATION_ATTRIBUTE_ILLEGAL_ARRAY_INITIALIZER =
    parameterized(PsiArrayInitializerMemberValue.class, AnnotationValueErrorContext.class, 
                  "annotation.attribute.illegal.array.initializer").withRawDescription((element, context) -> {
      return message("annotation.attribute.illegal.array.initializer", context.typeText());
    });
  public static final Parameterized<PsiNameValuePair, String> ANNOTATION_ATTRIBUTE_DUPLICATE =
    parameterized(PsiNameValuePair.class, String.class, "annotation.attribute.duplicate")
      .withRawDescription((pair, attribute) -> message("annotation.attribute.duplicate", attribute));
  public static final Parameterized<PsiNameValuePair, String> ANNOTATION_ATTRIBUTE_UNKNOWN_METHOD =
    error(PsiNameValuePair.class, "annotation.attribute.unknown.method")
      .withAnchor(pair -> requireNonNull(pair.getReference()).getElement())
      .withHighlightType(pair -> pair.getName() == null ? JavaErrorHighlightType.ERROR : JavaErrorHighlightType.WRONG_REF)
      .<String>withContext()
      .withRawDescription((pair, methodName) -> message("annotation.attribute.unknown.method", methodName));
  // Can be anchored on @FunctionalInterface annotation or at call site
  public static final Parameterized<PsiElement, PsiClass> LAMBDA_NOT_FUNCTIONAL_INTERFACE =
    parameterized(PsiElement.class, PsiClass.class, "lambda.not.a.functional.interface")
      .withRawDescription((element, aClass) -> message("lambda.not.a.functional.interface", aClass.getName()));
  // Can be anchored on @FunctionalInterface annotation or at call site
  public static final Parameterized<PsiElement, PsiClass> LAMBDA_NO_TARGET_METHOD =
    parameterized("lambda.no.target.method.found");
  // Can be anchored on @FunctionalInterface annotation or at call site
  public static final Parameterized<PsiElement, PsiClass> LAMBDA_MULTIPLE_TARGET_METHODS =
    parameterized(PsiElement.class, PsiClass.class, "lambda.multiple.sam.candidates")
      .withRawDescription((psi, aClass) -> message("lambda.multiple.sam.candidates", aClass.getName()));
  public static final Parameterized<PsiAnnotation, PsiClass> LAMBDA_FUNCTIONAL_INTERFACE_SEALED =
    parameterized("lambda.sealed.functional.interface");
  public static final Parameterized<PsiAnnotation, @NotNull List<PsiAnnotation.@NotNull TargetType>> ANNOTATION_NOT_APPLICABLE =
    error(PsiAnnotation.class, "annotation.not.applicable").<@NotNull List<PsiAnnotation.@NotNull TargetType>>withContext()
      .withValidator((annotation, types) -> {
        if (types.isEmpty()) {
          throw new IllegalArgumentException("types must not be empty");
        }
      })
      .withRawDescription((annotation, types) -> {
        String target = JavaPsiBundle.message("annotation.target." + types.get(0));
        PsiJavaCodeReferenceElement nameRef = annotation.getNameReferenceElement();
        return message("annotation.not.applicable", nameRef != null ? nameRef.getText() : annotation.getText(), target);
      });
  public static final Parameterized<PsiAnnotation, @NotNull List<String>> ANNOTATION_MISSING_ATTRIBUTE =
    error(PsiAnnotation.class, "annotation.missing.attribute")
      .withAnchor(annotation -> annotation.getNameReferenceElement())
      .<@NotNull List<String>>withContext()
      .withRawDescription((annotation, attributeNames) -> message(
          "annotation.missing.attribute", attributeNames.stream().map(attr -> "'" + attr + "'").collect(Collectors.joining(", "))));
  public static final Simple<PsiAnnotation> ANNOTATION_CONTAINER_WRONG_PLACE =
    error(PsiAnnotation.class, "annotation.container.wrong.place")
      .withRawDescription(annotation ->
                            message("annotation.container.wrong.place",
                                    requireNonNull(annotation.resolveAnnotationType()).getQualifiedName()));
  public static final Parameterized<PsiAnnotation, PsiClass> ANNOTATION_CONTAINER_NOT_APPLICABLE =
    parameterized(PsiAnnotation.class, PsiClass.class, "annotation.container.not.applicable")
      .withRawDescription((annotation, containerClass) -> {
        PsiAnnotation.TargetType[] targets = AnnotationTargetUtil.getTargetsForLocation(annotation.getOwner());
        String target = JavaPsiBundle.message("annotation.target." + targets[0]);
        return message("annotation.container.not.applicable", containerClass.getName(), target);
      });
  public static final Simple<PsiAnnotation> ANNOTATION_DUPLICATE =
    error(PsiAnnotation.class, "annotation.duplicate").withAnchor(annotation -> requireNonNull(annotation.getNameReferenceElement()));
  public static final Simple<PsiAnnotation> ANNOTATION_DUPLICATE_NON_REPEATABLE =
    error(PsiAnnotation.class, "annotation.duplicate.non.repeatable")
      .withAnchor(annotation -> requireNonNull(annotation.getNameReferenceElement()))
      .withRawDescription(annotation -> message(
        "annotation.duplicate.non.repeatable", requireNonNull(annotation.resolveAnnotationType()).getQualifiedName()));
  public static final Parameterized<PsiAnnotation, String> ANNOTATION_DUPLICATE_EXPLAINED =
    error(PsiAnnotation.class, "annotation.duplicate.explained")
      .withAnchor(annotation -> requireNonNull(annotation.getNameReferenceElement()))
      .<String>withContext()
      .withRawDescription((annotation, message) -> message("annotation.duplicate.explained", message));
  public static final Parameterized<PsiAnnotationMemberValue, String> ANNOTATION_MALFORMED_REPEATABLE_EXPLAINED =
    parameterized(PsiAnnotationMemberValue.class, String.class, "annotation.malformed.repeatable.explained")
      .withRawDescription((containerRef, message) -> message("annotation.malformed.repeatable.explained", message));

  public static final Simple<PsiAnnotation> SAFE_VARARGS_ON_RECORD_COMPONENT =
    error("safe.varargs.on.record.component");
  public static final Parameterized<PsiAnnotation, PsiMethod> SAFE_VARARGS_ON_FIXED_ARITY = parameterized("safe.varargs.on.fixed.arity");
  public static final Parameterized<PsiAnnotation, PsiMethod> SAFE_VARARGS_ON_NON_FINAL_METHOD =
    parameterized("safe.varargs.on.non.final.method");
  public static final Parameterized<PsiAnnotation, PsiMethod> OVERRIDE_ON_STATIC_METHOD = parameterized("override.on.static.method");
  public static final Parameterized<PsiAnnotation, PsiMethod> OVERRIDE_ON_NON_OVERRIDING_METHOD =
    parameterized("override.on.non-overriding.method");

  public static final Simple<PsiMethod> METHOD_DUPLICATE =
    error(PsiMethod.class, "method.duplicate")
      .withRange(JavaErrorFormatUtil::getMethodDeclarationTextRange)
      .withRawDescription(
        method -> message("method.duplicate", formatMethod(method), formatClass(requireNonNull(method.getContainingClass()))));
  public static final Simple<PsiReceiverParameter> RECEIVER_WRONG_CONTEXT =
    error(PsiReceiverParameter.class, "receiver.wrong.context").withAnchor(PsiReceiverParameter::getIdentifier);
  public static final Simple<PsiReceiverParameter> RECEIVER_STATIC_CONTEXT =
    error(PsiReceiverParameter.class, "receiver.static.context").withAnchor(PsiReceiverParameter::getIdentifier);
  public static final Simple<PsiReceiverParameter> RECEIVER_WRONG_POSITION =
    error(PsiReceiverParameter.class, "receiver.wrong.position").withAnchor(PsiReceiverParameter::getIdentifier);
  public static final Parameterized<PsiReceiverParameter, PsiType> RECEIVER_TYPE_MISMATCH =
    error(PsiReceiverParameter.class, "receiver.type.mismatch")
      .withAnchor(parameter -> requireNonNullElse(parameter.getTypeElement(), parameter)).withContext();
  public static final Parameterized<PsiReceiverParameter, @Nullable String> RECEIVER_NAME_MISMATCH =
    error(PsiReceiverParameter.class, "receiver.name.mismatch").withAnchor(PsiReceiverParameter::getIdentifier).withContext();
  // PsiMember = PsiClass | PsiEnumConstant
  public static final Parameterized<PsiMember, PsiMethod> CLASS_NO_ABSTRACT_METHOD =
    error(PsiMember.class, "class.must.implement.method")
      .withRange(member ->
                   member instanceof PsiEnumConstant enumConstant ? enumConstant.getNameIdentifier().getTextRange() :
                   member instanceof PsiClass aClass ? JavaErrorFormatUtil.getClassDeclarationTextRange(aClass) : null)
      .<PsiMethod>withContext()
      .withRawDescription((member, abstractMethod) -> {
        PsiClass aClass = member instanceof PsiClass cls ? cls : requireNonNull(member.getContainingClass());
        @PropertyKey(resourceBundle = JavaCompilationErrorBundle.BUNDLE) String messageKey;
        String referenceName;
        if (aClass instanceof PsiEnumConstantInitializer enumConstant) {
          messageKey = "class.must.implement.method.enum.constant";
          referenceName = enumConstant.getEnumConstant().getName();
        }
        else {
          messageKey = aClass.isEnum() || aClass.isRecord() || aClass instanceof PsiAnonymousClass
                       ? "class.must.implement.method"
                       : "class.must.implement.method.or.abstract";
          referenceName = formatClass(aClass, false);
        }
        return message(messageKey, referenceName, formatMethod(abstractMethod),
                       formatClass(requireNonNull(abstractMethod.getContainingClass()), false));
      });
  public static final Parameterized<PsiClass, PsiClass> CLASS_DUPLICATE =
    error(PsiClass.class, "class.duplicate")
      .withAnchor(cls -> requireNonNullElse(cls.getNameIdentifier(), cls))
      .withHighlightType(cls -> cls instanceof PsiImplicitClass ? JavaErrorHighlightType.FILE_LEVEL_ERROR : JavaErrorHighlightType.ERROR)
      .withRawDescription(cls -> message("class.duplicate", cls.getName()))
      .withContext();
  public static final Parameterized<PsiClass, PsiClass> CLASS_CYCLIC_INHERITANCE =
    error(PsiClass.class, "class.cyclic.inheritance")
      .withRange(JavaErrorFormatUtil::getClassDeclarationTextRange).<PsiClass>withContext()
      .withRawDescription((aClass, circularClass) -> message("class.cyclic.inheritance", formatClass(circularClass)));
  public static final Parameterized<PsiJavaCodeReferenceElement, PsiClass> CLASS_REFERENCE_LIST_DUPLICATE =
    parameterized(PsiJavaCodeReferenceElement.class, PsiClass.class, "class.reference.list.duplicate")
      .withRawDescription(
        (ref, target) -> message("class.reference.list.duplicate", formatClass(target), ref.getParent().getFirstChild().getText()));
  public static final Simple<PsiJavaCodeReferenceElement> CLASS_REFERENCE_LIST_NAME_EXPECTED =
    error("class.reference.list.name.expected");
  public static final Parameterized<PsiJavaCodeReferenceElement, PsiClass> CLASS_REFERENCE_LIST_INNER_PRIVATE =
    parameterized(PsiJavaCodeReferenceElement.class, PsiClass.class, "class.reference.list.inner.private")
      .withRawDescription((ref, target) -> message("class.reference.list.inner.private",
                                                   formatClass(target), formatClass(requireNonNull(target.getContainingClass()))));
  public static final Parameterized<PsiJavaCodeReferenceElement, PsiClass> CLASS_REFERENCE_LIST_NO_ENCLOSING_INSTANCE =
    parameterized(PsiJavaCodeReferenceElement.class, PsiClass.class, "class.reference.list.no.enclosing.instance")
      .withRawDescription((ref, target) -> message("class.reference.list.no.enclosing.instance", formatClass(target)));

  private static @NotNull <Psi extends PsiElement> Simple<Psi> error(@NotNull String key) {
    return new Simple<>(key);
  }

  private static @NotNull <Psi extends PsiElement> Simple<Psi> error(@SuppressWarnings("unused") @NotNull Class<Psi> psiClass,
                                                                     @NotNull String key) {
    return error(key);
  }

  private static @NotNull <Psi extends PsiElement, Context> Parameterized<Psi, Context> parameterized(
    @SuppressWarnings("unused") @NotNull Class<Psi> psiClass,
    @SuppressWarnings("unused") @NotNull Class<Context> contextClass,
    @NotNull String key) {
    return new Parameterized<>(key);
  }

  private static @NotNull <Psi extends PsiElement, Context> Parameterized<Psi, Context> parameterized(
    @NotNull String key) {
    return new Parameterized<>(key);
  }

  /**
   * Context for errors related to annotation value
   * @param method corresponding annotation method
   * @param expectedType expected value type
   * @param fromDefaultValue if true, the error is reported for the method default value, rather than for use site
   */
  public record AnnotationValueErrorContext(@NotNull PsiAnnotationMethod method, 
                                            @NotNull PsiType expectedType, 
                                            boolean fromDefaultValue) {
    public @NotNull String typeText() {
      return PsiTypesUtil.removeExternalAnnotations(expectedType()).getInternalCanonicalText();
    }

    public static @NotNull AnnotationValueErrorContext from(@NotNull PsiAnnotationMemberValue value,
                                                             @NotNull PsiAnnotationMethod method,
                                                             @NotNull PsiType expectedType) {
      boolean fromDefaultValue = PsiTreeUtil.isAncestor(method.getDefaultValue(), value, false);
      AnnotationValueErrorContext context = new AnnotationValueErrorContext(method, expectedType, fromDefaultValue);
      return context;
    }
  }
}
