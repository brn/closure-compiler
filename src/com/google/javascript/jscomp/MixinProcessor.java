package com.google.javascript.jscomp;

import java.util.Map;

import com.google.common.collect.Maps;
import com.google.javascript.jscomp.NodeTraversal.AbstractPostOrderCallback;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.jstype.FunctionType;
import com.google.javascript.rhino.jstype.JSType;
import com.google.javascript.rhino.jstype.ObjectType;

public class MixinProcessor implements HotSwapCompilerPass {
  private static final String MIXIN = "camp.mixin";

  private static final DiagnosticType MESSAGE_MIXIN_FIRST_ARGUMENT_IS_INVALID = DiagnosticType
      .error(
          "JSC_MESSAGE_MIXIN_FIRST_ARGUMENT_IS_INVALIDE",
          "A first argument of the camp.mixin must be a constructor function."
      );

  private static final DiagnosticType MESSAGE_MIXIN_SECOND_ARGUMENT_IS_INVALID = DiagnosticType
      .error(
          "JSC_MESSAGE_MIXIN_SECOND_ARGUMENT_IS_INVALIDE",
          "A second argument of the camp.mixin must be a constructor function."
      );

  private AbstractCompiler compiler;


  MixinProcessor(AbstractCompiler compiler) {
    this.compiler = compiler;
  }


  @Override
  public void hotSwapScript(Node scriptRoot, Node originalRoot) {
    compiler.process(this);
  }


  @Override
  public void process(Node externsRoot, Node root) {
    NodeTraversal.traverse(compiler, root, new MixinProcessCallback());
  }


  private final class MixinProcessCallback extends AbstractPostOrderCallback {
    @Override
    public void visit(NodeTraversal t, Node n, Node parent) {
      if (n.isCall()) {
        Node firstChild = n.getFirstChild();
        if (firstChild.isGetProp()) {
          String qname = firstChild.getQualifiedName();
          if (qname != null && qname.equals(MIXIN)) {
            processMixin(t, n, parent, firstChild);
          }
        }
      }
    }


    private void processMixin(NodeTraversal t, Node n, Node parent, Node firstChild) {
      Node dst = firstChild.getNext();
      if (dst == null || !isConstructor(dst)) {
        t.report(dst != null ? dst : firstChild, MESSAGE_MIXIN_FIRST_ARGUMENT_IS_INVALID);
        return;
      }
      Node src = dst.getNext();
      if (src == null || !isConstructor(src)) {
        t.report(src != null ? src : firstChild, MESSAGE_MIXIN_SECOND_ARGUMENT_IS_INVALID);
        return;
      }
      Node renameMapNode = src.getNext();
      Map<String, String> renameMap = Maps.newHashMap();
      if (renameMapNode != null) {
        if (renameMapNode.isObjectLit()) {
          for (Node key : renameMapNode.children()) {
            Node renamedNode = key.getFirstChild();
            if (renamedNode.isGetProp()) {
              String qname = renamedNode.getQualifiedName();
              if (qname != null && qname.indexOf(".prototype.") > -1) {
                String prop = NodeUtil.getPrototypePropertyName(renamedNode);
                renameMap.put(key.getString(), prop);
              }
            }
          }
        }
      }
      JSType dstType = dst.getJSType();
      JSType srcType = src.getJSType();
      FunctionType dstFnType = dstType.toMaybeFunctionType();
      FunctionType srcFnType = srcType.toMaybeFunctionType();
      ObjectType srcPrototype = srcFnType.getPrototype();
      ObjectType dstPrototype = dstFnType.getPrototype();
      for (String name : srcPrototype.getPropertyNames()) {
        String newPropName = renameMap.get(name);
        if (newPropName == null) {
          newPropName = name;
        }
        if (!dstPrototype.hasProperty(newPropName)) {
          dstPrototype.defineDeclaredProperty(newPropName,
              srcPrototype.getPropertyType(name),
              srcPrototype.getPropertyNode(name));
        }
      }
    }


    private boolean isConstructor(Node n) {
      JSType type = n.getJSType();
      return type != null && type.isConstructor();
    }
  }
}
