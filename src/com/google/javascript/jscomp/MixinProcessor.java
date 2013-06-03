package com.google.javascript.jscomp;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.google.common.base.Preconditions;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.javascript.jscomp.NodeTraversal.AbstractPostOrderCallback;
import com.google.javascript.rhino.IR;
import com.google.javascript.rhino.JSDocInfo;
import com.google.javascript.rhino.JSDocInfoBuilder;
import com.google.javascript.rhino.JSTypeExpression;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.Token;
import com.google.javascript.rhino.jstype.FunctionType;
import com.google.javascript.rhino.jstype.JSTypeRegistry;

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


  private void report(Node n, DiagnosticType diagnosticType,
      String... arguments) {
    JSError error = JSError.make(
        n.getSourceFileName(), n, diagnosticType, arguments);
    compiler.report(error);
  }


  private String createSpecializedName(String refName, String dstRefName) {
    return "JSComp$$" +
        refName.replaceAll("\\.", "\\$") + "$$" +
        dstRefName.replaceAll("\\.", "\\$");
  }


  private final class TraitRewriter {

    public void rewrite() {
      boolean isChanged = true;
      while (isChanged) {
        isChanged = false;
        for (TraitInfo info : traitInfoMap.values()) {
          if (extendsTrait(info)) {
            isChanged = true;
          }
        }
      }
    }


    private boolean extendsTrait(TraitInfo traitInfo) {
      List<String> requires = traitInfo.getRequires();
      boolean isChanged = false;
      for (String requireName : requires) {
        TraitInfo requireTrait = traitInfoMap.get(requireName);
        if (requireTrait == null) {
          report(traitInfo.getTopNode(), MESSAGE_REQUIRED_TRAIT_IS_NOT_EXISTS,
              requireName, traitInfo.getRefName());
          continue;
        }
        if (addProperties(traitInfo, requireTrait)) {
          isChanged = true;
        }
      }
      return isChanged;
    }


    private boolean addProperties(TraitInfo dst, TraitInfo src) {
      Map<String, TraitProperty> srcProps = src.getProperties();
      Map<String, TraitProperty> dstProps = dst.getProperties();
      boolean isChanged = false;

      for (TraitProperty srcProp : srcProps.values()) {
        TraitProperty alreadyDefinedProp = dstProps.get(srcProp.getName());

        if (srcProp.isProcessed()) {
          continue;
        }

        if (alreadyDefinedProp != null) {
          if (!alreadyDefinedProp.isImplicit()) {
            report(srcProp.getValueNode(), MESSAGE_DETECT_UNRESOLVED_METHOD,
                srcProp.getName(), srcProp.getRefName(), alreadyDefinedProp.getRefName());
          }

          continue;
        }
        isChanged = true;
        srcProp.setProcessed(true);
        Node key = IR.stringKey(srcProp.getName());
        Node srcKey = srcProp.getValueNode().getParent();
        Node qnameNode =
            NodeUtil.newQualifiedNameNode(convention, src.getRefName() + "." + srcProp.getName());
        qnameNode.copyInformationFromForTree(srcProp.getValueNode());
        key.copyInformationFrom(srcKey);
        key.addChildToBack(qnameNode);
        key.setJSDocInfo(srcKey.getJSDocInfo());
        TraitProperty newProp = (TraitProperty) srcProp.clone();
        newProp.setProcessed(false);
        newProp.setImplicit(false);
        dst.addProperty(newProp, key);
      }

      return isChanged;
    }
  }

  private TraitRewriter traitRewriter = new TraitRewriter();


  private void rewrite() {

    traitRewriter.rewrite();

    for (MixinInfo info : mixinInfoList) {
      List<String> traits = info.getTraits();
      for (String trait : traits) {
        TraitInfo tinfo = traitInfoMap.get(trait);
        if (tinfo == null) {
          report(info.getNode(), MESSAGE_REQUIRED_TRAIT_IS_NOT_EXISTS,
              trait, info.getType());
          continue;
        }
        assignPrototype(tinfo, info);
        addOverrideAsPrototype(info, tinfo);
      }

      info.getTopNode().detachFromParent();
      compiler.reportCodeChange();
    }

    for (TraitInfo tinfo : traitInfoMap.values()) {
      Node body = tinfo.getBody();
      Node call = body.getParent();
      Node cloned = body.cloneTree();
      cloned.copyInformationFromForTree(body);
      call.getParent().replaceChild(call, cloned);
      compiler.reportCodeChange();
    }
  }


  private void addOverrideAsPrototype(MixinInfo minfo, TraitInfo tinfo) {
    Map<String, Node> excludesMap = minfo.getExcludesMap();
    String qname = minfo.getType();
    Node top = minfo.getTopNode();
    Node parent = top.getParent();

    for (String key : excludesMap.keySet()) {
      Node def = excludesMap.get(key);
      Node keyNode = def.getParent();
      Node expr = createAssignment(qname, key, def);
      expr.copyInformationFromForTree(keyNode);
      Node assignmentNode = expr.getFirstChild();
      assignmentNode.setJSDocInfo(keyNode.getJSDocInfo());
      parent.addChildAfter(expr, top);
      compiler.reportCodeChange();
    }
  }


  private void assignPrototype(TraitInfo tinfo, MixinInfo minfo) {
    Map<String, TraitProperty> properties = tinfo.getProperties();
    String qname = minfo.getType();

    Map<String, Node> excludesMap = minfo.getExcludesMap();
    List<PropertyMutator> mutationTask = Lists.newArrayList();
    for (TraitProperty prop : properties.values()) {
      if (prop.isSpecialized()) {
        continue;
      }
      String name = prop.getName();
      if (excludesMap.containsKey(name)) {
        continue;
      }

      String specializedName = createSpecializedName(qname, prop.getName());
      mutationTask.add(new PropertyMutator(minfo, prop, qname, specializedName));
    }

    for (PropertyMutator mutator : mutationTask) {
      mutator.mutate();
    }
  }


  private Node createAssignment(String qname, String propName, String specializedName,
      String refName) {
    Node getprop = NodeUtil.newQualifiedNameNode(convention, qname + ".prototype." + propName);
    Node assignmentTarget = NodeUtil.newQualifiedNameNode(convention, refName + "."
        + specializedName);
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


  private final class PropertyMutator {
    private MixinInfo minfo;

    private TraitProperty prop;

    private String qname;

    private String specializedName;


    public PropertyMutator(
        MixinInfo minfo,
        TraitProperty prop,
        String qname,
        String specializedName) {
      this.minfo = minfo;
      this.prop = prop;
      this.qname = qname;
      this.specializedName = specializedName;
    }


    public void mutate() {
      Node top = minfo.getTopNode();
      Node parent = top.getParent();
      TraitInfo holder = prop.getHolder();
      holder.addNewPropertyFrom(prop, specializedName, qname);
      Node expr = createAssignment(qname, prop.getName(), specializedName, prop.getRefName());
      expr.copyInformationFromForTree(prop.getValueNode().getParent());
      parent.addChildAfter(expr, top);
      compiler.reportCodeChange();
    }
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


  private final class TraitProperty implements Cloneable {

    private Node valueNode;

    private String name;

    private String refName;

    private Node definitionPosition;

    private String thisType = "Object";

    private boolean isProcessed = false;

    private boolean isSpecialized = false;

    private boolean isImplicit = false;

    private TraitInfo holder;


    public TraitProperty(
        String name,
        String refName,
        Node valueNode,
        Node definitionPosition,
        TraitInfo holder) {
      this.name = name;
      this.refName = refName;
      this.valueNode = valueNode;
      this.definitionPosition = definitionPosition;
      this.holder = holder;
    }


    public Node getValueNode() {
      return valueNode;
    }


    public String getName() {
      return name;
    }


    public String getRefName() {
      return refName;
    }


    public Node getDefinitionPosition() {
      return definitionPosition;
    }


    public boolean isProcessed() {
      return isProcessed;
    }


    public void setProcessed(boolean isProcessed) {
      this.isProcessed = isProcessed;
    }


    public String getThisType() {
      return thisType;
    }


    public void setThisType(String thisType) {
      this.thisType = thisType;
    }


    public TraitInfo getHolder() {
      return this.holder;
    }


    public void markAsSpecialization() {
      this.isSpecialized = true;
    }


    public boolean isSpecialized() {
      return this.isSpecialized;
    }


    public void setImplicit(boolean isImplicit) {
      this.isImplicit = isImplicit;
    }


    public boolean isImplicit() {
      return this.isImplicit;
    }


    @Override
    public Object clone() {
      try {
        return super.clone();
      } catch (CloneNotSupportedException e) {
        throw new InternalError(e.toString());
      }
    }
  }


  private final class TraitInfo {
    private Node topNode;

    private String refName;

    private Node body;

    private Map<String, TraitProperty> properties = Maps.newHashMap();

    private List<String> requires = Lists.newArrayList();


    public TraitInfo(List<String> requires, Node topNode, Node body, String refName) {
      this.requires = requires;
      this.topNode = topNode;
      this.refName = refName;
      this.body = body;
      addImplicitProperties();
    }


    private void addImplicitProperties() {
      for (Node prop : body.children()) {
        String name = prop.getString();
        Node valueNode = prop.getFirstChild();
        TraitProperty property = new TraitProperty(name, refName, valueNode, topNode, this);
        property.setImplicit(true);
        Node value = prop.getFirstChild();
        if (value.isFunction()) {
          attachThisType(property.getThisType(), value);
        }
        properties.put(name, property);
      }
    }


    public void addNewPropertyFrom(
        TraitProperty old,
        String newName,
        @Nullable String newThisType) {

      Node keyNode = IR.stringKey(newName);
      Node oldValue = old.getValueNode();
      Node newValue = oldValue.cloneTree();
      keyNode.copyInformationFromForTree(oldValue.getParent());
      newValue.copyInformationFromForTree(oldValue);
      keyNode.addChildToBack(newValue);
      TraitProperty prop = new TraitProperty(newName, old.getRefName(),
          newValue, old.getDefinitionPosition(), this);
      if (newThisType != null) {
        prop.setThisType(newThisType);
      }
      prop.markAsSpecialization();
      addProperty(prop, keyNode);
    }


    private void attachThisType(String newThisType, Node target) {
      JSTypeExpression exp = new JSTypeExpression(IR.string(newThisType),
          target.getSourceFileName());
      JSDocInfo info = target.getJSDocInfo();
      if (info != null) {
        Method m = null;
        try {
          m = info.getClass().getDeclaredMethod("setThisType", JSTypeExpression.class);
          m.setAccessible(true);
        } catch (NoSuchMethodException e) {
          e.printStackTrace();
        } catch (SecurityException e) {
          e.printStackTrace();
        }
        try {
          if (m != null) {
            m.invoke(info, exp);
          }
        } catch (IllegalAccessException e) {
          e.printStackTrace();
        } catch (IllegalArgumentException e) {
          e.printStackTrace();
        } catch (InvocationTargetException e) {
          e.printStackTrace();
        }
      } else {
        JSDocInfoBuilder builder = new JSDocInfoBuilder(false);
        builder.recordThisType(exp);
        target.setJSDocInfo(builder.build(target));
      }
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


    public void addProperty(TraitProperty property, Node keyNode) {
      properties.put(property.getName(), property);
      body.addChildToBack(keyNode);
      Node value = keyNode.getFirstChild();
      if (value.isFunction()) {
        attachThisType(property.getThisType(), value);
      }
      compiler.reportCodeChange();
    }


    public Map<String, TraitProperty> getProperties() {
      return properties;
    }


    public List<String> getRequires() {
      return requires;
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


  private final class TraitMarkerProcessor {
    public void process(NodeTraversal t, Node n, Node parent, Node firstChild) {
      List<String> requires = Lists.newArrayList();
      Node body = getBody(t, n, firstChild, requires);

      if (body == null) {
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
        break;
      case Token.STRING_KEY:
        refName = NodeUtil.getBestLValueName(parent);
      }

      if (refName == null) {
        return;
      }

      Node top = getStatementBeginningNode(n);
      Preconditions.checkNotNull(top);

      TraitInfo traitInfo = new TraitInfo(requires, top, body, refName);
      traitInfoMap.put(refName, traitInfo);
    }


    private Node getBody(NodeTraversal t, Node n, Node firstChild, List<String> requires) {
      Node requiresOrBody = firstChild.getNext();
      if (requiresOrBody == null) {
        requiresOrBody = addEmptyBody(n);
      } else if (requiresOrBody.isArrayLit()) {
        for (Node require : requiresOrBody.children()) {
          if (!require.isName() && !NodeUtil.isGet(require)) {
            t.report(require, MESSAGE_TRAIT_EXTENDS_MUST_BE_THE_OBJ_LIT);
            return null;
          }
          String requiresName = require.getQualifiedName();
          if (requiresName == null) {
            t.report(require, MESSAGE_TRAIT_EXTENDS_MUST_BE_THE_OBJ_LIT);
            return null;
          }
          requires.add(requiresName);
        }
        requiresOrBody = requiresOrBody.getNext();
        if (requiresOrBody == null) {
          requiresOrBody = addEmptyBody(n);
        }
      }

      if (!requiresOrBody.isObjectLit()) {
        t.report(n, MESSAGE_TRAIT_MEMBER_DEFINITION_MUST_BE_THE_OBJ_LIT);
        return null;
      }

      return requiresOrBody;
    }


    private Node addEmptyBody(Node n) {
      Node requiresOrBody;
      Node body = IR.objectlit();
      body.copyInformationFromForTree(n);
      n.addChildToBack(body);
      compiler.reportCodeChange();
      requiresOrBody = body;
      return requiresOrBody;
    }
  }


  private final class TraitInfoCollector extends AbstractPostOrderCallback {

    private TraitMarkerProcessor traitMarkerProcessor = new TraitMarkerProcessor();


    public void visit(NodeTraversal t, Node n, Node parent) {
      if (n.isCall()) {
        Node firstChild = n.getFirstChild();
        if (firstChild.isGetProp()) {
          String qname = firstChild.getQualifiedName();
          if (qname == null) {
            return;
          }

          if (qname.equals(TRAIT_CALL)) {
            traitMarkerProcessor.process(t, n, parent, firstChild);
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
  }
}
