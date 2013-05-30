package com.google.javascript.jscomp;

import java.util.List;

import com.google.common.collect.Lists;
import com.google.javascript.jscomp.NodeTraversal.AbstractPostOrderCallback;
import com.google.javascript.rhino.IR;
import com.google.javascript.rhino.JSDocInfoBuilder;
import com.google.javascript.rhino.JSTypeExpression;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.Token;
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

  private AbstractCompiler compiler;

  private CodingConvention convention;


  MixinProcessor(AbstractCompiler compiler) {
    this.compiler = compiler;
    this.convention = compiler.getCodingConvention();
  }


  @Override
  public void hotSwapScript(Node scriptRoot, Node originalRoot) {
    compiler.process(this);
  }


  @Override
  public void process(Node externsRoot, Node root) {
    MixinProcessCallback cb = new MixinProcessCallback(root);
    NodeTraversal.traverse(compiler, root, cb);
    boolean isChanged = false;
    while (!isChanged) {
      isChanged = false;
      for (MixinInfo info : cb.getMixinInfo()) {
        info.setChanged(processMixin(info.getNodeTraversal(), info.getNode(), info.getFirstChild()));
      }
      for (MixinInfo info : cb.getMixinInfo()) {
        if (info.isChanged()) {
          isChanged = true;
        }
      }
    }
  }


  private final class MixinInfo {
    private Node node;

    private NodeTraversal t;

    private Node firstChild;

    private boolean isChanged = false;

    /**
     * @return the isChanged
     */
    public boolean isChanged() {
      return isChanged;
    }


    /**
     * @param isChanged the isChanged to set
     */
    public void setChanged(boolean isChanged) {
      this.isChanged = isChanged;
    }


    public MixinInfo(NodeTraversal t, Node node, Node firstChild) {
      this.node = node;
      this.t = t;
      this.firstChild = firstChild;
    }


    /**
     * @return the node
     */
    public Node getNode() {
      return node;
    }


    /**
     * @return the t
     */
    public NodeTraversal getNodeTraversal() {
      return t;
    }


    /**
     * @return the firstChild
     */
    public Node getFirstChild() {
      return firstChild;
    }

  }


  private final class MixinProcessCallback extends AbstractPostOrderCallback {

    private Node root;

    private List<MixinInfo> mixinInfoList = Lists.newArrayList();


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
            this.mixinInfoList.add(new MixinInfo(t, n, firstChild));
          }
        }
      }
    }


    public List<MixinInfo> getMixinInfo() {
      return this.mixinInfoList;
    }
  }


  private boolean processMixin(NodeTraversal t, Node n, Node firstChild) {
    Node dst = firstChild.getNext();
    boolean isChanged = false;
    if (dst == null || !isConstructor(dst)) {
      t.report(dst != null ? dst : firstChild, MESSAGE_MIXIN_FIRST_ARGUMENT_IS_INVALID);
      return false;
    }

    Node src = dst.getNext();
    if (src == null || !isConstructor(src)) {
      t.report(src != null ? src : firstChild, MESSAGE_MIXIN_SECOND_ARGUMENT_IS_INVALID);
      return false;
    }

    Node overrideMapNode = src.getNext();
    JSType dstType = dst.getJSType();
    JSType srcType = src.getJSType();
    FunctionType dstFnType = dstType.toMaybeFunctionType();
    FunctionType srcFnType = srcType.toMaybeFunctionType();
    ObjectType srcPrototype = srcFnType.getPrototype();
    ObjectType dstPrototype = dstFnType.getPrototype();
    List<ObjectType> interfaces = Lists.newArrayList(srcFnType.getImplementedInterfaces());
    dstFnType.setImplementedInterfaces(interfaces);

    if (overrideMapNode != null) {
      if (overrideMapNode.isObjectLit()) {
        for (Node keyNode : overrideMapNode.children()) {
          String key = keyNode.getString();
          Node target = keyNode.getFirstChild();
          dstPrototype.defineInferredProperty(key,
              target.getJSType(),
              target);
        }
      } else {
        t.report(overrideMapNode, MESSAGE_MIXIN_THIRD_ARGUMENT_IS_INVALID);
      }
    }

    JSTypeRegistry jsTypeRegistry = compiler.getTypeRegistry();

    Node comma = null;
    for (String name : srcPrototype.getPropertyNames()) {
      JSType type = srcPrototype.getPropertyType(name);
      FunctionType fnType = type.toMaybeFunctionType();

      if (fnType != null) {
        String displayName = fnType.getDisplayName();
        if (displayName != null && displayName.indexOf("Object.prototype.") > -1) {
          continue;
        }
      }

      if (!dstPrototype.hasOwnProperty(name)) {
        Node node = srcPrototype.getPropertyNode(name);
        Node assign = createDefinition(jsTypeRegistry, srcFnType, dstFnType, node, dstPrototype);
        if (assign != null) {
          dstPrototype.defineDeclaredProperty(name,
              assign.getFirstChild().getJSType(),
              assign.getFirstChild().getFirstChild());
          if (comma == null) {
            comma = assign;
          } else {
            comma = IR.comma(assign, comma);
          }
        }
      }
    }

    if (comma != null) {
      isChanged = true;
      Node top = getStatementBeginningNode(n);
      top.getParent().addChildAfter(top, IR.exprResult(comma));
    }

    return isChanged;
  }


  private Node createDefinition(
      JSTypeRegistry jsTypeRegistry,
      FunctionType srcType,
      FunctionType dstType,
      Node propertyNode, JSType prototypeType) {

    if (!NodeUtil.isGet(propertyNode)) {
      return null;
    }

    Node propertyNameNode = propertyNode.getLastChild();

    if (propertyNameNode.getString().equals("constructor")) {
      return null;
    }

    Node maybeAssign = propertyNode.getParent();
    if (!maybeAssign.isAssign()) {
      return null;
    }

    Node top = getStatementBeginningNode(maybeAssign);
    if (top == null) {
      return null;
    }

    JSType jstype = propertyNode.getJSType();
    if (jstype.isFunctionType()) {
      FunctionType fnType = jstype.toMaybeFunctionType();
      jstype = jsTypeRegistry
          .createFunctionTypeWithNewThisType(fnType, dstType.getInstanceType());
    }

    Node target = maybeAssign.getLastChild();
    Node renameTarget = propertyNode.getLastChild();
    Node assign;
    Node newProp = createSpecializedFunctionNode(srcType, dstType, propertyNode, maybeAssign,
        top, jstype, target, renameTarget);

    Node prototype = NodeUtil.newQualifiedNameNode(convention, dstType.getDisplayName()
        + ".prototype." + renameTarget.getString());

    if (propertyNode.isGetElem()) {
      prototype = IR.getelem(prototype.getFirstChild(), prototype.getLastChild());
    }

    prototype.setJSType(jstype);
    prototype.getFirstChild().setJSType(dstType.getPrototype());
    prototype.getFirstChild().getFirstChild().setJSType(dstType);
    prototype.getFirstChild().getLastChild()
        .setJSType(jsTypeRegistry.getNativeType(JSTypeNative.STRING_TYPE));
    prototype.getLastChild().setJSType(jsTypeRegistry.getNativeType(JSTypeNative.STRING_TYPE));

    assign = IR.assign(prototype, newProp.cloneTree());
    assign.setJSType(jstype);

    return assign;
  }


  private Node createSpecializedFunctionNode(
      FunctionType srcType,
      FunctionType dstType,
      Node propertyNode,
      Node maybeAssign,
      Node top,
      JSType jstype,
      Node target,
      Node renameTarget) {

    String propName = dstType.getDisplayName();
    String constructorName = srcType.getDisplayName() + "." + renameTarget.getString();
    String prototypeAccessor = constructorName + "$$JSComp$$" + getPropertyName(propName);
    Node newProp = NodeUtil.newQualifiedNameNode(convention, prototypeAccessor);
    Node assign = IR.assign(newProp, target.cloneTree());
    JSDocInfoBuilder builder = new JSDocInfoBuilder(false);
    builder.recordThisType(new JSTypeExpression(IR.string(dstType.getDisplayName()), target
        .getSourceFileName()));
    assign.setJSDocInfo(builder.build(assign));
    Node expr = NodeUtil.newExpr(assign);
    assign.setJSType(jstype);
    expr.setJSType(jstype);
    newProp.setJSType(jstype);
    expr.copyInformationFromForTree(maybeAssign);
    top.getParent().addChildAfter(expr, top);
    TypeRewriter rewriter = new TypeRewriter(jstype, propertyNode.getJSType(), srcType, dstType);
    NodeTraversal.traverseRoots(compiler, Lists.newArrayList(expr), rewriter);
    return newProp;
  }


  private String getPropertyName(String name) {
    return name.replaceAll("\\.", "_");
  }


  private Node getStatementBeginningNode(Node n) {
    while (n != null) {
      switch (n.getType()) {
      case Token.EXPR_RESULT:
      case Token.VAR:
      case Token.CONST:
      case Token.THROW:
        return n;

      default:
        if (NodeUtil.isFunctionDeclaration(n)) {
          return n;
        }
        n = n.getParent();
      }
    }

    return null;
  }


  private boolean isConstructor(Node n) {
    JSType type = n.getJSType();
    return type != null && type.isConstructor();
  }


  private final class TypeRewriter extends AbstractPostOrderCallback {
    private JSType newThisType;

    private JSType valueType;

    private FunctionType srcType;

    private FunctionType dstType;


    public TypeRewriter(JSType newThisType, JSType valueType, FunctionType srcType,
        FunctionType dstType) {
      this.newThisType = newThisType;
      this.valueType = valueType;
      this.srcType = srcType;
      this.dstType = dstType;
    }


    public void visit(NodeTraversal t, Node n, Node parent) {
      JSType type = n.getJSType();
      if (type == null) {
        return;
      }

      if (type.equals(valueType)) {
        n.setJSType(newThisType);
      }

      if (type.equals(srcType.getInstanceType())) {
        n.setJSType(dstType.getInstanceType());
      }
    }
  }
}
