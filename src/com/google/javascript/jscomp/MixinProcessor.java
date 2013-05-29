package com.google.javascript.jscomp;

import java.util.Map;

import com.google.common.collect.Maps;
import com.google.javascript.jscomp.NodeTraversal.AbstractPostOrderCallback;
import com.google.javascript.rhino.IR;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.jstype.FunctionType;
import com.google.javascript.rhino.jstype.JSType;
import com.google.javascript.rhino.jstype.JSTypeNative;
import com.google.javascript.rhino.jstype.JSTypeRegistry;
import com.google.javascript.rhino.jstype.ObjectType;

/**
 * 
 * @author aono_taketoshi The experimental implementation of the type mixin.
 * 
 */
public class MixinProcessor implements HotSwapCompilerPass {
  private static final String MIXIN_FN_NAME = "camp.mixin";
  private static final String DUMMY_RENAMING_LIT_NAME = "JSComp$Dummy_ObjectLiteral$";
  private int literalId = 0;

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

  static final DiagnosticType MESSAGE_MIXIN_RENAMING_PROPERTY_MAP_IS_INVALID = DiagnosticType
      .error(
          "JSC_MESSAGE_MIXIN_RENAME_PROPERTY_MAP_IS_INVALID",
          "The values of renaming map must be a string."
      );

  private static final String GET_RENAMED_PROPERTY_FN_NAME = "JSCompiler_renameProperty";

  private AbstractCompiler compiler;

  private CompilerOptions options;


  MixinProcessor(AbstractCompiler compiler, CompilerOptions options) {
    this.compiler = compiler;
    this.options = options;
  }


  @Override
  public void hotSwapScript(Node scriptRoot, Node originalRoot) {
    compiler.process(this);
  }


  @Override
  public void process(Node externsRoot, Node root) {
    NodeTraversal.traverse(compiler, root, new MixinProcessCallback(root));
  }


  private final class MixinProcessCallback extends AbstractPostOrderCallback {

    private Node root;


    public MixinProcessCallback(Node root) {
      this.root = root;
    }


    @Override
    public void visit(NodeTraversal t, Node n, Node parent) {
      if (n.isCall()) {
        Node firstChild = n.getFirstChild();
        if (firstChild.isGetProp()) {
          String qname = firstChild.getQualifiedName();
          if (qname != null && qname.equals(MIXIN_FN_NAME)) {
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
      MethodRenamingMap methodRenamingMap = new MethodRenamingMap(t, renameMapNode, root);
      methodRenamingMap.createRenamingMap();

      JSType dstType = dst.getJSType();
      JSType srcType = src.getJSType();
      FunctionType dstFnType = dstType.toMaybeFunctionType();
      FunctionType srcFnType = srcType.toMaybeFunctionType();
      ObjectType srcPrototype = srcFnType.getPrototype();
      ObjectType dstPrototype = dstFnType.getPrototype();
      for (String name : srcPrototype.getPropertyNames()) {
        String newPropName = methodRenamingMap.getRenamedPropertyName(name);
        if (newPropName == null) {
          newPropName = name;
        }
        if (!dstPrototype.hasOwnProperty(newPropName)) {
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


  private final class MethodRenamingMap {
    private Node renamingMapNode;

    private Node root;

    private NodeTraversal nodeTraversal;

    private JSTypeRegistry jsTypeRegistry = compiler.getTypeRegistry();

    private Map<String, String> renamingMap = Maps.newHashMap();


    public MethodRenamingMap(NodeTraversal nodeTraversal, Node objLit, Node root) {
      this.renamingMapNode = objLit;
      this.nodeTraversal = nodeTraversal;
      this.root = root;
    }


    public void createRenamingMap() {
      if (renamingMapNode == null) {
        return;
      }

      if (!renamingMapNode.isObjectLit()) {
        nodeTraversal.report(renamingMapNode, MESSAGE_MIXIN_THIRD_ARGUMENT_IS_INVALID);
        return;
      }

      for (Node key : renamingMapNode.children()) {
        Node renamedResultNode = key.getFirstChild();
        if (!renamedResultNode.isString()) {
          nodeTraversal.report(renamedResultNode,
              MESSAGE_MIXIN_RENAMING_PROPERTY_MAP_IS_INVALID);
          continue;
        }
        String name = renamedResultNode.getString();
        if (name != null) {
          renamingMap.put(key.getString(), name);
          if (options.propertyRenaming != PropertyRenamingPolicy.OFF) {
            addRenamedPropertyNameGetterFn(key, name);
          }
        }
      }
    }


    private void addRenamedPropertyNameGetterFn(Node key, String name) {
      Scope scope = nodeTraversal.getScope();
      boolean isDeclared = false;
      if (scope != null) {
        isDeclared = scope.getSlot(GET_RENAMED_PROPERTY_FN_NAME) != null;
      }

      if (!isDeclared) {
        Node function = buildFunctionNode();
        function.copyInformationFromForTree(key);
        JSType strType = jsTypeRegistry.getNativeType(JSTypeNative.STRING_TYPE);
        FunctionType type = jsTypeRegistry.createFunctionType(strType, strType);
        function.setJSType(type);
        root.getFirstChild().addChildToFront(function);
      }

      Node dummyKey = IR.stringKey(name);
      dummyKey.addChildToBack(IR.nullNode());
      Node dummy = IR.objectlit(dummyKey);
      Node var = NodeUtil.newVarNode(DUMMY_RENAMING_LIT_NAME + literalId, dummy);
      JSType anonymousType = jsTypeRegistry.createAnonymousObjectType(null);
      var.setJSType(anonymousType);
      literalId++;
      var.copyInformationFromForTree(key);
      compiler.getTopScope().declare(var.getFirstChild().getString(), var.getFirstChild(), anonymousType, compiler.getInput(nodeTraversal.getInputId()));
      root.getFirstChild().addChildrenToFront(var);
      
      key.detachChildren();
      key.addChildToBack(IR.call(IR.name(GET_RENAMED_PROPERTY_FN_NAME), IR.string(name)));
    }


    private Node buildFunctionNode() {
      Node paramList = IR.paramList(IR.name("a"));
      Node ret = IR.name("a");
      Node fnName = IR.name(GET_RENAMED_PROPERTY_FN_NAME);
      Node block = IR.block(IR.returnNode(ret));
      Node function = IR.function(fnName, paramList, block);
      return function;
    }


    public String getRenamedPropertyName(String beforeName) {
      return renamingMap.get(beforeName);
    }
  }
}
