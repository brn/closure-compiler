package com.google.javascript.jscomp;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import com.google.common.base.Preconditions;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.javascript.jscomp.MixinInfo.TraitImplementationInfo;
import com.google.javascript.jscomp.MixinInfo.TraitInfo;
import com.google.javascript.jscomp.MixinInfo.TraitProperty;
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

  private AbstractCompiler compiler;

  private CodingConvention convention;

  private final MixinInfoCollector mixinInfoCollector;


  MixinProcessor(AbstractCompiler compiler) {
    this.compiler = compiler;
    this.convention = compiler.getCodingConvention();
    this.mixinInfoCollector = new MixinInfoCollector();
  }


  @Override
  public void hotSwapScript(Node scriptRoot, Node originalRoot) {
    compiler.process(this);
  }


  @Override
  public void process(Node externsRoot, Node root) {
    NodeTraversal.traverse(compiler, root, mixinInfoCollector);
    rewrite();
  }


  private final class TraitRewriter {

    public void rewrite() {
      boolean isChanged = true;
      while (isChanged) {
        isChanged = false;
        for (TraitInfo info : mixinInfoCollector.getTraitInfoMap().values()) {
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
        TraitInfo requireTrait = mixinInfoCollector.getTraitInfoMap().get(requireName);
        if (requireTrait == null) {
          CampUtil.report(traitInfo.getTopNode(),
              MixinInfoConst.MESSAGE_REQUIRED_TRAIT_IS_NOT_EXISTS,
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

        if (srcProp.isPropagated(dst)) {
          continue;
        }

        TraitProperty alreadyDefinedProp = dstProps.get(srcProp.getName());
        if (alreadyDefinedProp != null) {
          if (srcProp.isRequire() && !alreadyDefinedProp.isRequire()) {
            continue;
          }

          if (!alreadyDefinedProp.isImplicit()) {
            CampUtil.report(dst.getTopNode(), MixinInfoConst.MESSAGE_DETECT_UNRESOLVED_METHOD,
                srcProp.getName(), srcProp.getCurrentHolderName(),
                alreadyDefinedProp.getLastHolderName());
          }

          continue;
        }

        isChanged = true;
        srcProp.markAsPropagated(dst);
        Node key = IR.stringKey(srcProp.getName());
        Node srcKey = srcProp.getValueNode().getParent();
        Node qnameNode =
            NodeUtil.newQualifiedNameNode(convention, src.getRefName() + "." + srcProp.getName());
        qnameNode.copyInformationFromForTree(srcProp.getValueNode());
        key.copyInformationFrom(srcKey);
        key.addChildToBack(qnameNode);
        key.setJSDocInfo(srcKey.getJSDocInfo());
        TraitProperty newProp = (TraitProperty) srcProp.clone();
        newProp.setCurrentHolderName(dst.getRefName());
        newProp.setImplicit(false);
        dst.addProperty(newProp, key);
      }

      return isChanged;
    }
  }

  private TraitRewriter traitRewriter = new TraitRewriter();


  private void rewrite() {

    traitRewriter.rewrite();
    Map<String, TraitInfo> traitInfoMap = mixinInfoCollector.getTraitInfoMap();

    for (MixinInfo info : mixinInfoCollector.getMixinInfoList()) {
      TraitImplementationInfo tiInfo = new TraitImplementationInfo();
      if (!mixinInfoCollector.getTypeInfoSet().contains(info.getType())) {
        CampUtil.report(info.getNode(), MixinInfoConst.MESSAGE_MIXIN_FIRST_ARGUMENT_IS_INVALID);
        continue;
      }

      List<String> traits = info.getTraits();
      for (String trait : traits) {
        TraitInfo tinfo = traitInfoMap.get(trait);
        if (tinfo == null) {
          CampUtil.report(info.getNode(), MixinInfoConst.MESSAGE_REQUIRED_TRAIT_IS_NOT_EXISTS,
              trait, info.getType());
          continue;
        }
        assignPrototype(tinfo, info, tiInfo);
      }

      addOverrideAsPrototype(info);
      tiInfo.checkUnimplementedProperty(info.getTopNode());
      info.getTopNode().detachFromParent();
      compiler.reportCodeChange();
    }

    for (TraitInfo tinfo : traitInfoMap.values()) {
      Node body = tinfo.getBody();
      String name = "JSC$tmp$" + tinfo.getRefName().replaceAll("\\.", "_");
      Node constructor = IR.function(IR.name(name), IR.paramList(), IR.block());
      JSDocInfoBuilder builder = new JSDocInfoBuilder(false);
      builder.recordConstructor();
      JSDocInfo info = builder.build(constructor);
      constructor.setJSDocInfo(info);
      Node beginning = CampUtil.getStatementBeginningNode(body);
      Node top = beginning.getParent();
      top.addChildBefore(constructor, beginning);
      Node target = constructor;
      Map<String, TraitProperty> propMap = tinfo.getProperties();
      for (String key : propMap.keySet()) {
        TraitProperty prop = propMap.get(key);
        Node node = prop.getValueNode().getParent();
        Node prototype = NodeUtil.newQualifiedNameNode(convention,
            name + ".prototype." + node.getString());
        Node assignment;
        if (prop.isRequire()) {
          Node paramList = IR.paramList();
          JSDocInfo jsDocInfo = node.getJSDocInfo();
          if (jsDocInfo != null && jsDocInfo.getParameterCount() > 0) {
            for (String paramName : jsDocInfo.getParameterNames()) {
              paramList.addChildToBack(IR.name(paramName));
            }
          }
          Node assignmentValue = IR.function(IR.name(""), paramList, IR.block());
          assignment = IR.assign(prototype, assignmentValue);
          assignment.setJSDocInfo(node.getJSDocInfo());
        } else {
          if (prop.isImplicit()) {
            assignment = IR.assign(prototype, prop.getValueNode().cloneTree());
          } else {
            assignment = createAssignment(prop, prototype);
          }
        }

        Node exprResult = NodeUtil.newExpr(assignment);
        exprResult.copyInformationFromForTree(node);
        top.addChildAfter(exprResult, target);
        target = exprResult;
      }
      Node call = body.getParent();
      call.getParent().replaceChild(call, IR.newNode(IR.name(name)));
      compiler.reportCodeChange();
    }
  }


  private void addOverrideAsPrototype(MixinInfo minfo) {
    Map<String, Node> excludesMap = minfo.getExcludesMap();
    String qname = minfo.getType();
    Node top = minfo.getTopNode();
    Node parent = top.getParent();

    for (String key : excludesMap.keySet()) {
      Node def = excludesMap.get(key);
      if (def.isGetProp()) {
        String valueName = def.getQualifiedName();
        if (valueName != null && valueName.equals(MixinInfoConst.REQUIRE)) {
          CampUtil.report(def, MixinInfoConst.MESSAGE_REQUIRE_IS_NOT_ALLOWED_HERE);
        }
      }
      Node keyNode = def.getParent();
      Node expr = createAssignment(qname, key, def);
      expr.copyInformationFromForTree(keyNode);
      Node assignmentNode = expr.getFirstChild();
      assignmentNode.setJSDocInfo(keyNode.getJSDocInfo());
      parent.addChildAfter(expr, top);
      compiler.reportCodeChange();
    }

  }


  private void assignPrototype(
      TraitInfo tinfo,
      MixinInfo minfo,
      TraitImplementationInfo tiInfo) {
    Map<String, TraitProperty> properties = tinfo.getProperties();

    String qname = minfo.getType();
    Node top = minfo.getTopNode();
    Map<String, Node> excludesMap = minfo.getExcludesMap();
    List<PropertyMutator> mutationTask = Lists.newArrayList();
    for (TraitProperty prop : properties.values()) {
      String name = prop.getName();
      if (excludesMap.containsKey(name)) {
        continue;
      }

      if (prop.isRequire()) {
        tiInfo.addImplementationInfo(prop);
        continue;
      }

      if (!tiInfo.checkConfliction(prop, top)) {
        continue;
      }

      mutationTask.add(new PropertyMutator(minfo, prop, qname));
    }

    for (PropertyMutator mutator : mutationTask) {
      mutator.mutate(tiInfo);
    }
  }


  private Node createAssignment(String qname, TraitProperty prop) {
    Node getprop = NodeUtil.newQualifiedNameNode(convention, qname + ".prototype." + prop.getName());
    Node expr = NodeUtil.newExpr(createAssignment(prop, getprop));
    return expr;
  }


  private Node createAssignment(String qname, String propName, Node def) {
    Node getprop = NodeUtil.newQualifiedNameNode(convention, qname + ".prototype." + propName);
    Node assign = IR.assign(getprop, def.cloneTree());
    Node expr = NodeUtil.newExpr(assign);
    return expr;
  }


  private Node createAssignment(TraitProperty prop, Node lhs) {
    Node parentNameNode = NodeUtil.newQualifiedNameNode(convention, prop.getRefName()
        + "." + prop.getName());
    if (prop.isFunction()) {
      Node call;
      if (prop.isThisAccess()) {
        call = IR.call(IR.getprop(parentNameNode, IR.string("call")), IR.thisNode());
      } else {
        call = IR.call(parentNameNode);
      }
      Node valueNode = prop.getValueNode();
      Node paramList = valueNode.getFirstChild().getNext();
      Node newParamList = IR.paramList();
      for (Node paramNode : paramList.children()) {
        call.addChildToBack(paramNode.cloneNode());
        newParamList.addChildToBack(paramNode.cloneNode());
      }
      Node function = IR.function(IR.name(""), newParamList,
          IR.block(IR.returnNode(call)));
      function.copyInformationFromForTree(valueNode);
      function.setJSDocInfo(valueNode.getJSDocInfo());
      return IR.assign(lhs, function);
    }
    return IR.assign(lhs, parentNameNode);

  }


  private final class PropertyMutator {
    private MixinInfo minfo;

    private TraitProperty prop;

    private String qname;


    public PropertyMutator(
        MixinInfo minfo,
        TraitProperty prop,
        String qname) {
      this.minfo = minfo;
      this.prop = prop;
      this.qname = qname;
    }


    public void mutate(TraitImplementationInfo tiInfo) {
      Node top = minfo.getTopNode();
      Node parent = top.getParent();
      tiInfo.markAsImplemented(prop);
      Node expr = createAssignment(qname, prop);
      expr.copyInformationFromForTree(prop.getValueNode().getParent());
      parent.addChildAfter(expr, top);
      compiler.reportCodeChange();
    }
  }
}
