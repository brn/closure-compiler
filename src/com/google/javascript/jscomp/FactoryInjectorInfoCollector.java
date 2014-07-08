package com.google.javascript.jscomp;

import java.util.List;

import com.google.javascript.jscomp.FactoryInjectorInfo.InjectInfo;
import com.google.javascript.jscomp.FactoryInjectorInfo.TypeInfo;
import com.google.javascript.jscomp.NodeTraversal.AbstractPostOrderCallback;
import com.google.javascript.rhino.JSDocInfo;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.Token;

/**
 * Collect constructors informations for the factory method injection.
 * 
 * @author aono_taketoshi
 * 
 */
public class FactoryInjectorInfoCollector {

  private static final String INJECT = "camp.utils.dependencies.inject";

  private static final String INJECT_ONCE = "camp.utils.dependencies.inject.once";

  static final DiagnosticType MESSAGE_INJECT_FIRST_ARGUMENT_IS_INVALID =
      DiagnosticType.error("JSC_MSG_RESOLVE_FIRST_ARGUMENT_IS_INVALID",
          "A first argument of " + INJECT + " is must be a constructor.");

  static final DiagnosticType MESSAGE_INJECT_SECOND_ARGUMENT_IS_INVALID =
      DiagnosticType.error("JSC_MSG_RESOLVE_FIRST_ARGUMENT_IS_INVALID",
          "A first argument of " + INJECT
              + " is must be a function which return the dependencies.");

  private AbstractCompiler compiler;

  private FactoryInjectorInfo factoryInjectorInfo;


  public FactoryInjectorInfoCollector(AbstractCompiler compiler,
      FactoryInjectorInfo factoryInjectorInfo) {
    this.compiler = compiler;
    this.factoryInjectorInfo = factoryInjectorInfo;
  }


  /**
   * Collect informations of constructors.
   * 
   * @param externRoot
   *          The extern file root node.
   * @param root
   *          The root node of main codes.
   */
  public void process(Node externRoot, Node root) {
    NodeTraversal.traverse(compiler, root, new MarkerProcessCallback());
    NodeTraversal.traverse(compiler, root, new InjectionAliasFinder());
  }


  /**
   * The processor of the constructors.
   * 
   * @author aono_taketoshi
   * 
   */
  private final class TypeMarkerProcessor {
    /**
     * Collect constructor informations to inject factory method.
     * 
     * @param t
     *          Current NodeTraversal
     * @param n
     *          The target node.
     * @param parent
     *          The parent node of a target node.
     */
    public void process(NodeTraversal t, Node n, Node parent) {
      TypeInfo typeInfo = null;
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
        JSDocInfo info = NodeUtil.getBestJSDocInfo(n);
        typeInfo = new TypeInfo(name, n, info);
        factoryInjectorInfo.putTypeInfo(typeInfo);
      }
    }
  }


  /**
   * Collect 'camp.utils.dependencies.inject' call informations.
   * 
   * @author aono_taketoshi
   * 
   */
  private final class InjectMarkerProcessor {
    public void process(NodeTraversal t, Node n, Node parent, boolean isInjectOnce) {
      if (isValidInjectCall(t, n)) {
        factoryInjectorInfo.addInjectInfo(new InjectInfo(n, isInjectOnce));
      }
    }


    /**
     * Check whether 'camp.utils.dependencies.inject' call arguments are valid
     * or not.
     * 
     * @param t
     *          Current NodeTraversal.
     * @param n
     *          A target node.
     * @return true if 'camp.utils.dependencies.inject' call arguments are
     *         valid, otherwise false.
     */
    private boolean isValidInjectCall(NodeTraversal t, Node n) {
      Node firstChild = n.getFirstChild().getNext();
      if (firstChild == null || (!firstChild.isName() && !NodeUtil.isGet(firstChild))) {
        t.report(n, MESSAGE_INJECT_FIRST_ARGUMENT_IS_INVALID);
        return false;
      }

      Node secondArg = firstChild.getNext();
      if (secondArg == null) {
        t.report(n, MESSAGE_INJECT_SECOND_ARGUMENT_IS_INVALID);
        return false;
      }

      return true;
    }
  }


  /**
   * Visitor implementation for the processors.
   * 
   * @author aono_taketoshi
   * 
   */
  private final class MarkerProcessCallback extends AbstractPostOrderCallback {

    private TypeMarkerProcessor typeMarkerProcessor = new TypeMarkerProcessor();

    private InjectMarkerProcessor injectMarkerProcessor = new InjectMarkerProcessor();


    @Override
    public void visit(NodeTraversal t, Node n, Node parent) {
      if (n.isCall()) {
        Node getprop = n.getFirstChild();
        if (getprop.isGetProp()) {
          String qname = getprop.getQualifiedName();
          if (qname != null) {
            boolean isInject = qname.equals(INJECT);
            boolean isInjectOnce = qname.equals(INJECT_ONCE);
            if (isInject || isInjectOnce) {
              injectMarkerProcessor.process(t, n, parent, isInjectOnce);
            }
          }
        }
      } else if (n.isFunction() && t.getScopeDepth() == 1) {
        JSDocInfo info = NodeUtil.getBestJSDocInfo(n);
        if (info != null && info.isConstructor()) {
          typeMarkerProcessor.process(t, n, parent);
        }
      }
    }
  }


  /**
   * Visitor implementations to find aliased types.
   * 
   * @author aono_taketoshi
   * 
   */
  private final class InjectionAliasFinder extends AbstractPostOrderCallback {
    @Override
    public void visit(NodeTraversal t, Node n, Node parent) {
      if (t.getScopeDepth() == 1) {
        switch (n.getType()) {
        case Token.ASSIGN:
          this.checkAssignment(t, n);
          break;

        case Token.VAR:
          this.checkVar(t, n);
        }
      }
    }


    /**
     * Inspect assignment expression to check whether type is aliased or not.
     * 
     * @param t
     *          Current NodeTraversal.
     * @param n
     *          A target node.
     */
    private void checkAssignment(NodeTraversal t, Node n) {
      if (t.getScopeDepth() == 1) {
        Node child = n.getFirstChild();
        String qualifiedName = child.getQualifiedName();

        if (qualifiedName != null) {

          Node rvalue = child.getNext();
          if (NodeUtil.isGet(rvalue) || rvalue.isName()) {
            String name = rvalue.getQualifiedName();
            List<TypeInfo> info = factoryInjectorInfo.getTypeInfoMap().get(name);
            if (info.size() > 0) {
              this.createAliasTypeInfoFrom(n, info, name, qualifiedName);
            }
          }
        }
      }
    }


    /**
     * Inspect variable statement to check whether type is aliased or not.
     * 
     * @param t
     *          Current NodeTraversal.
     * @param n
     *          A target node.
     */
    private void checkVar(NodeTraversal t, Node n) {
      if (t.getScopeDepth() == 1) {
        Node nameNode = n.getFirstChild();
        Node rvalue = nameNode.getFirstChild();
        if (rvalue != null && (rvalue.isName() || NodeUtil.isGet(rvalue))) {
          String name = rvalue.getQualifiedName();
          List<TypeInfo> info = factoryInjectorInfo.getTypeInfoMap().get(name);
          if (info.size() > 0) {
            createAliasTypeInfoFrom(n, info, name, nameNode.getString());
          }
        }
      }
    }


    /**
     * Create the type information for the aliased type.
     * 
     * @param aliasPoint
     * @param info
     * @param aliasName
     * @param name
     */
    private void createAliasTypeInfoFrom(Node aliasPoint, List<TypeInfo> info, String aliasName,
        String name) {
      TypeInfo aliasInfo = new TypeInfo(name, aliasPoint, null);
      factoryInjectorInfo.putTypeInfo(aliasInfo);
      aliasInfo.setAlias();
      aliasInfo.setAliasName(aliasName);
    }
  }
}
