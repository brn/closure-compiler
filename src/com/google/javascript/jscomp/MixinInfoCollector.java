package com.google.javascript.jscomp;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.javascript.jscomp.MixinInfo.TraitInfo;
import com.google.javascript.jscomp.NodeTraversal.AbstractPostOrderCallback;
import com.google.javascript.rhino.IR;
import com.google.javascript.rhino.JSDocInfo;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.Token;

public class MixinInfoCollector extends AbstractPostOrderCallback {

  private final List<MixinInfo> mixinInfoList = Lists.newArrayList();

  private final Set<String> typeInfoSet = Sets.newHashSet();

  private final Map<String, TraitInfo> traitInfoMap = Maps.newHashMap();

  private final TraitMarkerProcessor traitMarkerProcessor = new TraitMarkerProcessor();

  private final MixinMarkerProcessor mixinMarkerProcessor = new MixinMarkerProcessor();

  private final TypeInfoMarkerProcessor typeInfoMarkerProcessor = new TypeInfoMarkerProcessor();


  /**
   * @return the mixinInfoList
   */
  public List<MixinInfo> getMixinInfoList() {
    return mixinInfoList;
  }


  /**
   * @return the typeInfoSet
   */
  public Set<String> getTypeInfoSet() {
    return typeInfoSet;
  }


  /**
   * @return the traitInfoMap
   */
  public Map<String, TraitInfo> getTraitInfoMap() {
    return traitInfoMap;
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

      Node top = CampUtil.getStatementBeginningNode(n);
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
            t.report(require, MixinInfoConst.MESSAGE_TRAIT_EXTENDS_MUST_BE_THE_OBJ_LIT);
            return null;
          }
          String requiresName = require.getQualifiedName();
          if (requiresName == null) {
            t.report(require, MixinInfoConst.MESSAGE_TRAIT_EXTENDS_MUST_BE_THE_OBJ_LIT);
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
        t.report(n, MixinInfoConst.MESSAGE_TRAIT_MEMBER_DEFINITION_MUST_BE_THE_OBJ_LIT);
        return null;
      }

      return requiresOrBody;
    }


    private Node addEmptyBody(Node n) {
      Node requiresOrBody;
      Node body = IR.objectlit();
      body.copyInformationFromForTree(n);
      n.addChildToBack(body);
      CampUtil.reportCodeChange();
      requiresOrBody = body;
      return requiresOrBody;
    }
  }


  private final class MixinMarkerProcessor {
    public void process(NodeTraversal t, Node n, Node parent, Node firstChild) {
      Node dst = firstChild.getNext();

      if (dst == null || (!dst.isName() && !NodeUtil.isGet(dst))) {
        t.report(dst != null ? dst : firstChild,
            MixinInfoConst.MESSAGE_MIXIN_FIRST_ARGUMENT_IS_INVALID);
        return;
      }

      String qname = dst.getQualifiedName();
      if (qname == null) {
        t.report(dst != null ? dst : firstChild,
            MixinInfoConst.MESSAGE_MIXIN_FIRST_ARGUMENT_IS_INVALID);
        return;
      }

      Node src = dst.getNext();
      if (src == null || !src.isArrayLit()) {
        t.report(src != null ? src : firstChild,
            MixinInfoConst.MESSAGE_MIXIN_SECOND_ARGUMENT_IS_INVALID);
        return;
      }

      List<String> traits = Lists.newArrayList();
      for (Node trait : src.children()) {
        if (!trait.isName() && !NodeUtil.isGet(trait)) {
          t.report(src != null ? src : firstChild,
              MixinInfoConst.MESSAGE_MIXIN_SECOND_ARGUMENT_IS_INVALID);
          return;
        }
        String traitName = trait.getQualifiedName();
        if (traitName == null) {
          t.report(src != null ? src : firstChild,
              MixinInfoConst.MESSAGE_MIXIN_SECOND_ARGUMENT_IS_INVALID);
          return;
        }
        traits.add(traitName);
      }

      Node excludesMapNode = src.getNext();
      if (excludesMapNode != null && !excludesMapNode.isObjectLit()) {
        t.report(src != null ? src : firstChild,
            MixinInfoConst.MESSAGE_MIXIN_THIRD_ARGUMENT_IS_INVALID);
        return;
      }

      Map<String, Node> excludesMap = Maps.newHashMap();
      if (excludesMapNode != null) {
        for (Node key : excludesMapNode.children()) {
          excludesMap.put(key.getString(), key.getFirstChild());
        }
      }

      Node top = CampUtil.getStatementBeginningNode(n);
      if (top == null) {
        return;
      }

      MixinInfo mixinInfo = new MixinInfo(n, top, qname, traits, excludesMap);
      mixinInfoList.add(mixinInfo);
    }
  }


  private final class TypeInfoMarkerProcessor {
    public void process(NodeTraversal t, Node n, Node parent) {
      String name = null;
      if (NodeUtil.isFunctionDeclaration(n)) {
        name = n.getFirstChild().getString();
      } else {
        if (parent.isAssign()) {
          name = parent.getFirstChild().getQualifiedName();
        } else if (NodeUtil.isVarDeclaration(parent)) {
          name = parent.getString();
        } else if (parent.isStringKey()) {
          name = NodeUtil.getBestLValueName(parent);
        }
      }

      if (name != null) {
        typeInfoSet.add(name);
      }
    }
  }


  public void visit(NodeTraversal t, Node n, Node parent) {
    if (n.isCall()) {
      Node firstChild = n.getFirstChild();
      if (firstChild.isGetProp()) {
        String qname = firstChild.getQualifiedName();
        if (qname == null) {
          return;
        }

        if (qname.equals(MixinInfoConst.TRAIT_CALL)
            && checkScope(t, n, MixinInfoConst.TRAIT_CALL)) {
          traitMarkerProcessor.process(t, n, parent, firstChild);
        } else if (qname.equals(MixinInfoConst.MIXIN_FN_NAME)
            && checkScope(t, n, MixinInfoConst.MIXIN_FN_NAME)) {
          mixinMarkerProcessor.process(t, n, parent, firstChild);
        }
      }
    } else if (n.isFunction() && t.getScopeDepth() == 1) {
      JSDocInfo info = NodeUtil.getBestJSDocInfo(n);
      if (info != null && info.isConstructor()) {
        typeInfoMarkerProcessor.process(t, n, parent);
      }
    }
  }


  private boolean checkScope(NodeTraversal t, Node n, String name) {
    if (t.getScopeDepth() != 1) {
      t.report(n, MixinInfoConst.MESSAGE_FUNCTION_MUST_BE_CALLED_IN_GLOBAL_SCOPE, name);
      return false;
    }
    return true;
  }

}
