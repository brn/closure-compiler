package com.google.javascript.jscomp;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import com.google.javascript.jscomp.CampTypeInfo.PrototypeProperty;
import com.google.javascript.jscomp.CampTypeInfo.TypeInfo;
import com.google.javascript.jscomp.NodeTraversal.AbstractPostOrderCallback;
import com.google.javascript.rhino.IR;
import com.google.javascript.rhino.JSDocInfo;
import com.google.javascript.rhino.JSDocInfoBuilder;
import com.google.javascript.rhino.JSTypeExpression;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.jstype.JSType;
import com.google.javascript.rhino.jstype.JSTypeRegistry;

public class TypeInfoCollectPass implements HotSwapCompilerPass {

  private AbstractCompiler compiler;

  private CampTypeInfo campTypeInfo;

  private static final String MIXINED_CONSTRUCTOR = "camp.mixin";

  private static final String MIXIN = "camp.compiler.mixin";

  private CodingConvention convention;


  public TypeInfoCollectPass(AbstractCompiler compiler, CampTypeInfo campTypeInfo) {
    this.compiler = compiler;
    this.campTypeInfo = campTypeInfo;
    this.convention = compiler.getCodingConvention();
  }


  @Override
  public void hotSwapScript(Node scriptRoot, Node originalRoot) {
    compiler.process(this);
  }


  @Override
  public void process(Node externsRoot, Node root) {
    NodeTraversal.traverse(compiler, root, new TypeCollectCallback());
    joinInfo();
    checkInheritance();
    for (TypeInfo t : campTypeInfo.getTypeInfoMap().values()) {
      System.out.println(t.getName());
      for (PrototypeProperty p : t.getPrototypeMap().values()) {
        System.out.println(p.getName());
      }
    }
  }


  private void joinInfo() {
    Multimap<String, PrototypeProperty> prototypeInfoMap = campTypeInfo.getPrototypeInfoMap();
    for (TypeInfo t : campTypeInfo.getTypeInfoMap().values()) {
      String name = t.getName();
      if (prototypeInfoMap.containsKey(name)) {
        Collection<PrototypeProperty> prototypeCollection = prototypeInfoMap.get(name);
        for (PrototypeProperty p : prototypeCollection) {
          t.putPrototype(p);
        }
      }
    }
  }


  private void checkInheritance() {
    Map<String, TypeInfo> typeInfoMap = campTypeInfo.getTypeInfoMap();
    for (TypeInfo typeInfo : typeInfoMap.values()) {
      if (typeInfo.isInheritancePropagated()) {
        continue;
      }
      extendPrototype(typeInfoMap, typeInfo, new ArrayList<TypeInfo>());
    }
  }


  private void extendPrototype(Map<String, TypeInfo> typeInfoMap, TypeInfo typeInfo,
      List<TypeInfo> derives) {
    JSDocInfo info = typeInfo.getJSDocInfo();
    JSTypeExpression type = info.getBaseType();
    if (type != null) {
      String baseClass = type.getRoot().getFirstChild().getString();
      if (typeInfoMap.containsKey(baseClass)) {
        derives.add(typeInfo);
        TypeInfo baseTypeInfo = typeInfoMap.get(baseClass);
        for (PrototypeProperty prop : baseTypeInfo.getPrototypeMap().values()) {
          PrototypeProperty propagatedProp = (PrototypeProperty) prop.clone();
          for (TypeInfo derive : derives) {
            derive.putPrototype(propagatedProp);
          }
        }
        typeInfo.setInheritancePropagated(true);
        extendPrototype(typeInfoMap, baseTypeInfo, derives);
      }
    }
  }


  private enum DefinitionType {
    ASSIGN,
    VAR,
    FN_DECL,
    OBJ_LIT,
    ELSE
  }


  private final class TypeCollectCallback extends AbstractPostOrderCallback {
    @Override
    public void visit(NodeTraversal t, Node n, Node parent) {
      if (parent == null)
        return;
      Definition definition = getDefinition(n, parent);
      if (!definition.maybeConstructor()) {

        return;
      }
      JSDocInfo jsDocInfo = NodeUtil.getBestJSDocInfo(n);
      if (jsDocInfo == null || !jsDocInfo.isConstructor()) {
        if (definition.getDefinitionType() == DefinitionType.ASSIGN) {
          String refName = parent.getFirstChild().getQualifiedName();
          if (refName.indexOf(".prototype.") > -1 || refName.endsWith(".prototype")) {
            casePrototype(t, n, parent);
            return;
          }
        }
        return;
      }

      Node constructor = null;
      String refName;
      boolean maybeMixin = true;
      switch (definition.getDefinitionType()) {
      case FN_DECL:
        refName = n.getFirstChild().getString();
        constructor = n;
        maybeMixin = false;
        break;
      case ASSIGN:
        refName = parent.getFirstChild().getQualifiedName();
        constructor = parent.getFirstChild();
        break;
      case VAR:
        refName = parent.getString();
        constructor = n.getFirstChild();
        break;
      case OBJ_LIT:
        refName = NodeUtil.getBestLValueName(parent);
        constructor = n;
        break;
      default:
        return;
      }

      if (maybeMixin && processMixinIfNecessary(refName, constructor, jsDocInfo)) {
        return;
      }
      TypeInfo typeInfo = new TypeInfo(refName, constructor, jsDocInfo);
      campTypeInfo.putTypeInfo(typeInfo);
    }


    private void casePrototype(NodeTraversal t, Node n, Node parent) {
      String ctorName = NodeUtil.getPrototypeClassName(parent.getFirstChild())
          .getQualifiedName();
      String propName = NodeUtil.getPrototypePropertyName(parent.getFirstChild());
      if (ctorName != null && propName != null) {
        PrototypeProperty prop = new PrototypeProperty(ctorName, propName, parent
            .getFirstChild().getNext(),
            NodeUtil.getBestJSDocInfo(n), true);
        campTypeInfo.putPrototypeInfo(prop);
      }
    }


    private boolean processMixinIfNecessary(String refName, Node constructor, JSDocInfo jsDocInfo) {
      Node next = constructor.getNext();
      if (next != null && next.isCall()) {
        Node prop = next.getFirstChild();
        if (prop != null && prop.isGetProp()) {
          String qname = prop.getQualifiedName();
          if (qname.equals(MIXINED_CONSTRUCTOR)) {
            caseMixin(refName, next, jsDocInfo);
            return true;
          }
        }
      }
      return false;
    }


    private void caseMixin(String refName, Node call, JSDocInfo jsDocInfo) {
      Node constructor = call.getLastChild();
      if (!constructor.isFunction()) {
        return;
      }
      Node parent = call.getParent();
      Node maybeObjLit = call.getFirstChild().getNext();
      if (maybeObjLit.isObjectLit()) {
        TypeInfo typeInfo = new TypeInfo(refName, constructor, jsDocInfo);
        for (Node keyNode : maybeObjLit.children()) {
          String key = keyNode.getString();
          Node target = keyNode.getFirstChild();
          boolean isCast = target.isCast();
          String traitName = isCast ? target.getFirstChild().getQualifiedName()
              : target.getQualifiedName();
          typeInfo.addMixin(traitName);
          if (traitName != null) {
            Node body = constructor.getLastChild();
            Node thisProperty = NodeUtil.newQualifiedNameNode(convention, "this." + key);
            Node newCall = IR.newNode(target.cloneTree());
            Node assignment = IR.assign(thisProperty, newCall);
            JSDocInfo typeParam = keyNode.getJSDocInfo();
            if (typeParam != null) {
              JSDocInfoBuilder builder = new JSDocInfoBuilder(false);
              JSTypeExpression type = typeParam.getType();
              builder.recordType(type);
              assignment.setJSDocInfo(builder.build(assignment));
              keyNode.setJSDocInfo(null);
            }
            body.addChildToFront(NodeUtil.newExpr(assignment));
            Node top = CampUtil.getStatementBeginningNode(call);
            Node mixin = NodeUtil.newQualifiedNameNode(convention, MIXIN);
            Node mixinCall = NodeUtil.newCallNode(mixin,
                NodeUtil.newQualifiedNameNode(convention, refName),
                NodeUtil.newQualifiedNameNode(convention, traitName));
            Node expr = NodeUtil.newExpr(mixinCall);
            expr.copyInformationFromForTree(top);
            top.getParent().addChildAfter(expr, top);
            CampUtil.reportCodeChange();
          }
        }
        constructor.detachFromParent();
        parent.replaceChild(call, constructor);
        campTypeInfo.putTypeInfo(typeInfo);
      }
    }


    private Definition getDefinition(Node n, Node parent) {
      return new Definition(n, parent);
    }


    private final class Definition {
      private DefinitionType definitionType;


      public Definition(Node n, Node parent) {
        if (NodeUtil.isFunctionDeclaration(n)) {
          this.definitionType = DefinitionType.FN_DECL;
        } else if (parent.isAssign()) {
          this.definitionType = DefinitionType.ASSIGN;
        } else if (NodeUtil.isVarDeclaration(parent)) {
          this.definitionType = DefinitionType.VAR;
        } else if (parent.isStringKey()) {
          this.definitionType = DefinitionType.OBJ_LIT;
        } else {
          this.definitionType = DefinitionType.ELSE;
        }
      }


      public boolean maybeConstructor() {
        return this.definitionType != DefinitionType.ELSE;
      }


      public DefinitionType getDefinitionType() {
        return this.definitionType;
      }
    }
  }
}
