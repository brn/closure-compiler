package com.google.javascript.jscomp;

import java.util.Map;

import com.google.javascript.jscomp.CampTypeInfo.PrototypeProperty;
import com.google.javascript.jscomp.CampTypeInfo.TypeInfo;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.jstype.FunctionType;
import com.google.javascript.rhino.jstype.ObjectType;

public class CampCodingConvention extends CodingConventions.Proxy {
  private CampTypeInfo campTypeInfo;


  public CampCodingConvention(CampTypeInfo campTypeInfo) {
    super(new ClosureCodingConvention());
    this.campTypeInfo = campTypeInfo;
  }


  /**
   * Closure's goog.inherits adds a {@code superClass_} property to the
   * subclass, and a {@code constructor} property.
   */
  @Override
  public void applySubclassRelationship(FunctionType parentCtor,
      FunctionType childCtor, SubclassType type) {
    super.applySubclassRelationship(parentCtor, childCtor, type);
    if (type == SubclassType.MIXIN) {
      Map<String, TypeInfo> typeInfoMap = campTypeInfo.getTypeInfoMap();
      TypeInfo typeInfo = typeInfoMap.get(childCtor.getReferenceName());
      if (typeInfo != null) {
        typeInfo = typeInfoMap.get(parentCtor.getReferenceName());
        if (typeInfo != null) {
          Map<String, PrototypeProperty> propMap = typeInfo.getPrototypeMap();
          for (PrototypeProperty p : propMap.values()) {
            ObjectType prototype = childCtor.getImplicitPrototype();
            prototype.defineInferredProperty(p.getName(), null, p.getValue());
          }
        }
      }
    }
  }


  @Override
  public SubclassRelationship getClassesDefinedByCall(Node callNode) {
    SubclassRelationship relationship =
        super.getClassesDefinedByCall(callNode);
    if (relationship != null) {
      return relationship;
    }

    Node prop = callNode.getFirstChild();
    if (prop.isGetProp()) {
      String qname = prop.getQualifiedName();
      if (qname != null && qname.equals("camp.compiler.mixin")) {
        Node target = prop.getNext();
        Node mixin = target.getNext();
        return new SubclassRelationship(SubclassType.MIXIN, target, mixin);
      }
    }
    return null;
  }
}
