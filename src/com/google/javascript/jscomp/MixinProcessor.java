package com.google.javascript.jscomp;

import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.javascript.jscomp.NodeTraversal.AbstractPostOrderCallback;
import com.google.javascript.jscomp.NodeTraversal.AbstractScopedCallback;
import com.google.javascript.rhino.IR;
import com.google.javascript.rhino.JSDocInfo;
import com.google.javascript.rhino.JSDocInfoBuilder;
import com.google.javascript.rhino.JSTypeExpression;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.Token;

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

  static final DiagnosticType MESSAGE_MIXIN_HAS_CIRCULAR_REFERENCE = DiagnosticType.error(
      "JSC_MESSAGE_MIXIN_HAS_CIRCULAR_REFERENCE",
      "The trait can not mixin self.");

  private AbstractCompiler compiler;

  private CodingConvention convention;

  private final List<MixinInfo> mixinInfoList = Lists.newArrayList();


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
    TraitInfoCollector cb = new TraitInfoCollector();
    NodeTraversal.traverse(compiler, root, cb);
    rewrite();
  }


  private final class ThisTypeInfo {
    private String thisType;

    private String specializedName;


    public ThisTypeInfo(String thisType, String specializedName) {
      this.thisType = thisType;
      this.specializedName = specializedName;
    }


    /**
     * @return the thisType
     */
    public String getThisType() {
      return thisType;
    }


    /**
     * @return the specializedName
     */
    public String getSpecializedName() {
      return specializedName;
    }

  }

  private ArrayListMultimap<String, ThisTypeInfo> specializedNameMap = ArrayListMultimap.create();


  private void report(Node n, DiagnosticType diagnosticType,
      String... arguments) {
    JSError error = JSError.make(
        n.getSourceFileName(), n, diagnosticType, arguments);
    compiler.report(error);
  }


  private void rewrite() {

    for (MixinInfo info : mixinInfoList) {
      List<String> traits = info.getTraits();
      String typeName = info.getType();

      Set<String> overridedSet = Sets.newHashSet();
      if (traitInfoMap.containsKey(typeName)) {
        TraitInfo traitInfo = traitInfoMap.get(typeName);
        for (String trait : info.getTraits()) {
          if (trait.equals(typeName)) {
            report(info.getNode(), MESSAGE_MIXIN_HAS_CIRCULAR_REFERENCE);
            return;
          }
          TraitInfo tinfo = traitInfoMap.get(trait);
          if (tinfo == null) {
            continue;
          }
          addProperties(traitInfo, tinfo, info, overridedSet);
        }
        addOverride(info, traitInfo, overridedSet, true);
      } else {
        for (String trait : traits) {
          TraitInfo tinfo = traitInfoMap.get(trait);
          if (tinfo == null) {
            continue;
          }
          assignPrototype(tinfo, info, overridedSet);
          addOverride(info, tinfo, overridedSet, false);
        }

      }
      info.getTopNode().detachFromParent();
      compiler.reportCodeChange();
    }

    for (TraitInfo tinfo : traitInfoMap.values()) {
      Node body = tinfo.getBody();
      Node call = body.getParent();
      Node top = tinfo.getTopNode();
      Node cloned = body.cloneTree();
      cloned.copyInformationFromForTree(body);

      List<ThisTypeInfo> specializedNameList = specializedNameMap.get(tinfo.getRefName());

      if (specializedNameList.size() > 0) {
        for (ThisTypeInfo thisTypeInfo : specializedNameList) {
          Node clone = body.cloneTree();
          clone.copyInformationFromForTree(body);
          addThisType(clone, thisTypeInfo.getThisType());
          Node var = NodeUtil.newVarNode(thisTypeInfo.getSpecializedName(), clone);
          var.copyInformationFromForTree(top);
          top.getParent().addChildAfter(var, top);
          compiler.reportCodeChange();
        }

      }
      addThisType(cloned, "Object");
      call.getParent().replaceChild(call, cloned);
      compiler.reportCodeChange();
    }
  }


  private void addOverride(MixinInfo minfo, TraitInfo tinfo, Set<String> overridedSet,
      boolean isTrait) {
    Map<String, Node> excludesMap = minfo.getExcludesMap();

    if (!isTrait) {
      String qname = minfo.getType();
      Node top = minfo.getTopNode();
      Node parent = top.getParent();
      addOverrideAsPrototype(overridedSet, excludesMap, qname, top, parent);
    } else {
      Node target = tinfo.getBody();
      addOverrideToTraitBody(overridedSet, excludesMap, target);
    }
  }


  private void addOverrideToTraitBody(Set<String> overridedSet, Map<String, Node> excludesMap,
      Node target) {
    for (String key : excludesMap.keySet()) {
      if (!overridedSet.contains(key)) {
        Node def = excludesMap.get(key);
        Node keyNode = def.getParent();
        Node newKey = IR.stringKey(key);
        newKey.addChildToBack(def.cloneTree());
        attachJSDocInfo(keyNode, newKey);
        target.addChildToBack(newKey);
        compiler.reportCodeChange();
      }
    }
  }


  private void addOverrideAsPrototype(Set<String> overridedSet, Map<String, Node> excludesMap,
      String qname, Node top, Node parent) {
    for (String key : excludesMap.keySet()) {
      if (!overridedSet.contains(key)) {
        Node def = excludesMap.get(key);
        Node keyNode = def.getParent();
        Node expr = createAssignment(qname, key, def);
        expr.copyInformationFromForTree(keyNode);
        attachJSDocInfo(keyNode, expr);
        parent.addChildAfter(expr, top);
        compiler.reportCodeChange();
      }
    }
  }


  private void addThisType(Node body, String thisType) {
    for (Node key : body.children()) {
      if (key.getFirstChild().isFunction()) {
        Node fn = key.getFirstChild();
        JSDocInfoBuilder builder = new JSDocInfoBuilder(false);
        builder.recordThisType(new JSTypeExpression(IR.string(thisType), body.getSourceFileName()));
        fn.setJSDocInfo(builder.build(fn));
      }
    }
  }


  private void assignPrototype(TraitInfo tinfo, MixinInfo minfo, Set<String> overrideSet) {
    Node lit = tinfo.getBody();
    String refName = tinfo.getRefName();
    String qname = minfo.getType();

    String specializedName = "JSComp$$" + refName.replaceAll("\\.", "\\$") + "$$"
        + qname.replaceAll("\\.", "\\$");
    Map<String, Node> excludesMap = minfo.getExcludesMap();
    ThisTypeInfo thisTypeInfo = new ThisTypeInfo(qname, specializedName);
    specializedNameMap.put(refName, thisTypeInfo);

    Node top = minfo.getTopNode();
    Node parent = top.getParent();

    for (Node keyNode : lit.children()) {
      String key = keyNode.getString();
      Node expr = null;
      if (excludesMap.containsKey(key)) {
        overrideSet.add(key);
        Node def = excludesMap.get(key);
        expr = createAssignment(qname, key, def);
        expr.copyInformationFromForTree(keyNode);
      } else {
        expr = createAssignment(qname, key, specializedName);
        expr.copyInformationFromForTree(keyNode);
      }
      attachJSDocInfo(keyNode, expr);
      parent.addChildAfter(expr, top);
      compiler.reportCodeChange();
    }
  }


  private void attachJSDocInfo(Node keyNode, Node expr) {
    JSDocInfo info = keyNode.getJSDocInfo();
    if (info != null) {
      Node target = expr;
      if (expr.isExprResult()) {
        target = expr.getFirstChild();
      }
      info.setAssociatedNode(target);
      target.setJSDocInfo(info);
    }
  }


  private void addProperties(TraitInfo traitInfo, TraitInfo tinfo, MixinInfo minfo,
      Set<String> overridedSet) {
    Node target = traitInfo.getBody();
    Node lit = tinfo.getBody();
    Map<String, Node> excludesMap = minfo.getExcludesMap();
    Set<String> overrideSet = Sets.newHashSet();

    for (Node keyNode : lit.children()) {
      String key = keyNode.getString();
      Node newKey = null;
      if (excludesMap.containsKey(key)) {
        overrideSet.add(key);
        Node def = excludesMap.get(key);
        newKey = IR.stringKey(key);
        newKey.addChildToBack(def.cloneTree());
      } else {
        newKey = keyNode.cloneTree();
      }
      newKey.copyInformationFromForTree(keyNode);
      attachJSDocInfo(keyNode, newKey);
      target.addChildToBack(newKey);
      compiler.reportCodeChange();
    }
  }


  private Node createAssignment(String qname, String propName, String specializedName) {
    Node getprop = NodeUtil.newQualifiedNameNode(convention, qname + ".prototype." + propName);
    Node assignmentTarget = NodeUtil.newQualifiedNameNode(convention, specializedName + "."
        + propName);
    Node assign = IR.assign(getprop, assignmentTarget);
    Node expr = NodeUtil.newExpr(assign);
    return expr;
  }


  private Node createAssignment(String qname, String propName, Node def) {
    Node getprop = NodeUtil.newQualifiedNameNode(convention, qname + ".prototype." + propName);
    Node assign = IR.assign(getprop, def.cloneTree());
    Node expr = NodeUtil.newExpr(assign);
    return expr;
  }


  private final class MixinInfo {
    private Node node;

    private String type;

    private List<String> traits;

    private Node topNode;

    private Map<String, Node> excludesMap;


    public MixinInfo(
        Node node,
        Node topNode,
        String type,
        List<String> traits,
        Map<String, Node> excludesMap) {
      this.node = node;
      this.type = type;
      this.traits = traits;
      this.topNode = topNode;
      this.excludesMap = excludesMap;
    }


    /**
     * @return the node
     */
    public Node getNode() {
      return node;
    }


    /**
     * @return the type
     */
    public String getType() {
      return type;
    }


    /**
     * @return the traits
     */
    public List<String> getTraits() {
      return traits;
    }


    public Node getTopNode() {
      return this.topNode;
    }


    /**
     * @return the excludesMap
     */
    public Map<String, Node> getExcludesMap() {
      return excludesMap;
    }
  }


  private final class TraitInfo {
    private Node topNode;

    private String refName;

    private Node body;


    public TraitInfo(Node topNode, Node body, String refName) {
      this.topNode = topNode;
      this.refName = refName;
      this.body = body;
    }


    /**
     * @return the topNode
     */
    public Node getTopNode() {
      return topNode;
    }


    /**
     * @return the refName
     */
    public String getRefName() {
      return refName;
    }


    /**
     * @return the body
     */
    public Node getBody() {
      return body;
    }

  }

  private final Map<String, TraitInfo> traitInfoMap = Maps.newHashMap();

  private static final String TRAIT_CALL = "camp.trait";


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


  private final class TraitInfoCollector extends AbstractPostOrderCallback {
    public void visit(NodeTraversal t, Node n, Node parent) {
      if (n.isCall()) {
        Node firstChild = n.getFirstChild();
        if (firstChild.isGetProp()) {
          String qname = firstChild.getQualifiedName();
          if (qname == null) {
            return;
          }

          if (qname.equals(TRAIT_CALL)) {
            processTrait(t, n, parent, firstChild);
          } else if (qname.equals(MIXIN_FN_NAME)) {
            processMixin(t, n, parent, firstChild);
          }
        }
      }
    }


    private void processMixin(NodeTraversal t, Node n, Node parent, Node firstChild) {
      Node dst = firstChild.getNext();

      if (dst == null || (!dst.isName() && !NodeUtil.isGet(dst))) {
        t.report(dst != null ? dst : firstChild, MESSAGE_MIXIN_FIRST_ARGUMENT_IS_INVALID);
        return;
      }

      String qname = dst.getQualifiedName();
      if (qname == null) {
        t.report(dst != null ? dst : firstChild, MESSAGE_MIXIN_FIRST_ARGUMENT_IS_INVALID);
        return;
      }

      Node src = dst.getNext();
      if (src == null || !src.isArrayLit()) {
        t.report(src != null ? src : firstChild, MESSAGE_MIXIN_SECOND_ARGUMENT_IS_INVALID);
        return;
      }

      List<String> traits = Lists.newArrayList();
      for (Node trait : src.children()) {
        if (!trait.isName() && !NodeUtil.isGet(trait)) {
          t.report(src != null ? src : firstChild, MESSAGE_MIXIN_SECOND_ARGUMENT_IS_INVALID);
          return;
        }
        String traitName = trait.getQualifiedName();
        if (traitName == null) {
          t.report(src != null ? src : firstChild, MESSAGE_MIXIN_SECOND_ARGUMENT_IS_INVALID);
          return;
        }
        traits.add(traitName);
      }

      Node excludesMapNode = src.getNext();
      if (excludesMapNode != null && !excludesMapNode.isObjectLit()) {
        t.report(src != null ? src : firstChild, MESSAGE_MIXIN_THIRD_ARGUMENT_IS_INVALID);
        return;
      }

      Map<String, Node> excludesMap = Maps.newHashMap();
      if (excludesMapNode != null) {
        for (Node key : excludesMapNode.children()) {
          excludesMap.put(key.getString(), key.getFirstChild());
        }
      }

      Node top = getStatementBeginningNode(n);
      if (top == null) {
        return;
      }

      MixinInfo mixinInfo = new MixinInfo(n, top, qname, traits, excludesMap);
      mixinInfoList.add(mixinInfo);
    }


    private void processTrait(NodeTraversal t, Node n, Node parent, Node firstChild) {
      Node body = firstChild.getNext();
      
      if (body == null) {
        return;
      }
      
      if (!body.isObjectLit()) {
        return;
      }
      String refName = null;

      switch (parent.getType()) {
      case Token.NAME:
        if (parent.getParent().isVar()) {
          refName = parent.getString();
        }
        break;
      case Token.ASSIGN:
        String qname = parent.getFirstChild().getQualifiedName();
        refName = qname;
      }

      if (refName == null) {
        return;
      }

      Node top = getStatementBeginningNode(n);
      if (top == null) {
        return;
      }
      TraitInfo traitInfo = new TraitInfo(top, body, refName);
      traitInfoMap.put(refName, traitInfo);
    }
  }
}
