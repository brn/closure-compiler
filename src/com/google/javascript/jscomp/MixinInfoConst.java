package com.google.javascript.jscomp;

public class MixinInfoConst {
  static final String MIXIN_FN_NAME = "camp.mixin";

  static final String TRAIT_CALL = "camp.trait";

  static final String REQUIRE = "camp.trait.require";

  static final DiagnosticType MESSAGE_MIXIN_FIRST_ARGUMENT_IS_INVALID = DiagnosticType
      .error(
          "JSC_MESSAGE_MIXIN_FIRST_ARGUMENT_IS_INVALIDE",
          "A first argument of the camp.mixin must be a constructor function."
      );

  static final DiagnosticType MESSAGE_MIXIN_SECOND_ARGUMENT_IS_INVALID = DiagnosticType
      .error(
          "JSC_MESSAGE_MIXIN_SECOND_ARGUMENT_IS_INVALIDE",
          "A second argument of the camp.mixin must be a constructor function."
      );

  static final DiagnosticType MESSAGE_MIXIN_THIRD_ARGUMENT_IS_INVALID = DiagnosticType
      .error(
          "JSC_MESSAGE_MIXIN_THIRD_ARGUMENT_IS_INVALIDE",
          "A third argument of the camp.mixin must be an object literal."
      );

  static final DiagnosticType MESSAGE_MIXIN_HAS_CIRCULAR_REFERENCE = DiagnosticType.error(
      "JSC_MESSAGE_MIXIN_HAS_CIRCULAR_REFERENCE",
      "The trait can not mixin self.");

  static final DiagnosticType MESSAGE_TRAIT_MEMBER_DEFINITION_MUST_BE_THE_OBJ_LIT = DiagnosticType
      .error(
          "JSC_MESSAGE_TRAIT_MEMBER_DEFINITION_MUST_BE_THE_OBJ_LIT",
          "The property defintions of camp.trait must be the object literal.");

  static final DiagnosticType MESSAGE_TRAIT_EXTENDS_MUST_BE_THE_OBJ_LIT = DiagnosticType
      .error(
          "JSC_MESSAGE_TRAIT_MEMBER_DEFINITION_MUST_BE_THE_OBJ_LIT",
          "The requirements of the trait must be the array literal of the trait name.");

  static final DiagnosticType MESSAGE_REQUIRED_TRAIT_IS_NOT_EXISTS = DiagnosticType
      .error(
          "JSC_MESSAGE_REQUIRED_TRAIT_IS_NOT_EXISTS",
          "The trait {0} required from {1} is not exists.");

  static final DiagnosticType MESSAGE_DETECT_UNRESOLVED_METHOD = DiagnosticType.error(
      "JSC_MESSAGE_DETECT_UNRESOLVED_METHOD",
      "The function {0} defined in {1} conflict with the function of {2}."
      );

  static final DiagnosticType MESSAGE_FUNCTION_MUST_BE_CALLED_IN_GLOBAL_SCOPE = DiagnosticType
      .error(
          "JSC_MESSAGE_FUNCTION_MUST_BE_CALLED_IN_GLOBAL_SCOPE",
          "The function {0} must be called in the global scope."
      );

  static final DiagnosticType MESSAGE_REQUIRED_PROPERTY_IS_NOT_IMPLMENTED = DiagnosticType.error(
      "JSC_MESSAGE_REUIRED_PROPERTY_IS_NOT_IMPLMENTED",
      "The property {0} required by {1} (first defined in {2}) is not implemented."
      );

  static final DiagnosticType MESSAGE_REQUIRE_IS_NOT_ALLOWED_HERE = DiagnosticType.error(
      "JSC_MESSAGE_REQUIRE_IS_NOT_ALLOWED_HERE",
      "The " + REQUIRE + " is not allowed here."
      );
  
  static final String TEMP_VAR_ANME = "JSComp_tmpTrait$";
  
  static final String THIS_TYPE_TEMPLATE_TYPE = "JSComp_$TraitThisType";
}
