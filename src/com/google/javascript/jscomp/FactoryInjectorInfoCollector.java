package com.google.javascript.jscomp;

import java.util.List;

import com.google.common.base.Preconditions;
import com.google.javascript.jscomp.FactoryInjectorInfo.BindedConstructorInfo;
import com.google.javascript.jscomp.FactoryInjectorInfo.BindedKeyInfo;
import com.google.javascript.jscomp.FactoryInjectorInfo.BinderInfo;
import com.google.javascript.jscomp.FactoryInjectorInfo.NewWithInfo;
import com.google.javascript.jscomp.FactoryInjectorInfo.TypeInfo;
import com.google.javascript.jscomp.NodeTraversal.AbstractPostOrderCallback;
import com.google.javascript.rhino.JSDocInfo;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.Token;

public class FactoryInjectorInfoCollector {

  private static final String NEW_WITH = "camp.utils.dependencies.newWith";

  private static final String BINDER = "camp.utils.dependencies.binder.bind";

  private static final String BINDER_SINGLETON = "camp.utils.dependencies.binder.bindSingleton";

  private static final String KEY_TO = "to";

  private static final String KEY_AS = "as";

  private static final String SINGLETON_SCOPES = "camp.utils.dependencies.Scopes.SINGLETON";

  static final DiagnosticType MESSAGE_RESOLVE_FIRST_ARGUMENT_IS_INVALID =
      DiagnosticType.error("JSC_MSG_RESOLVE_FIRST_ARGUMENT_IS_INVALID",
          "A first argument of {0} is must be a constructor.");

  static final DiagnosticType MESSAGE_RESOLVE_SECOND_ARGUMENT_IS_INVALID =
      DiagnosticType.error("JSC_MSG_RESOLVE_SECOND_ARGUMENT_IS_INVALID",
          "A second argument of {0} is must be a binding object.");

  static final DiagnosticType MESSAGE_INJECTION_IS_ALREADY_SPECIFIED = DiagnosticType
      .error("JSC_MSG_INJECTION_IS_AMBIGUOUS",
          "The method injection target {0} of a constructor {1} is already specified.");

  static final DiagnosticType MESSAGE_INVALID_METHOD_INJECTION_SPECIFICATION = DiagnosticType
      .error(
          "JSC_MSG_INVALID_METHOD_SPECIFICATION",
          "The string expression '{0}' is invalid method injection specification.");

  static final DiagnosticType MESSAGE_INJECTION_TARGET_NOT_EXISTS_OR_PARSABLE = DiagnosticType
      .error(
          "JSC_MSG_METHOD_NOT_FOUND",
          "The injection target {0} of the constructor {1} is not exists in prototype chain or is not parsable. "
              +
              "Compiler is parse only the prototypes which are the function directly assigned. " +
              "If you declare prototype which is non-trivial style, " +
              "you should specify not only a method name but also parameters " +
              "in 'camp.injections.Injector.inject' call, like 'setFoo(foo,bar,baz)'.");

  static final DiagnosticType MESSAGE_FIRST_ARGUMENT_OF_INJECT_IS_INVALID = DiagnosticType
      .error(
          "JSC_MSG_FIRST_ARGUMENT_OF_INJECT_IS_INVALID.",
          "The first argument of camp.injections.Injector.inject must be a constructor function.");

  static final DiagnosticType MESSAGE_SECOND_ARGUMENT_OF_INJECT_IS_INVALID = DiagnosticType
      .error(
          "JSC_MSG_SECOND_ARGUMENT_OF_INJECT_IS_INVALID.",
          "The second argument and rest of camp.injections.Injector.inject must be a string expression which is method name of injection target.");

  static final DiagnosticType MESSAGE_NEW_WITH_FIRST_ARGUMENT_IS_INVALID =
      DiagnosticType.error("JSC_MSG_RESOLVE_FIRST_ARGUMENT_IS_INVALID",
          "A first argument of " + NEW_WITH + " is must be a constructor.");

  static final DiagnosticType MESSAGE_NEW_WITH_SECOND_ARGUMENT_IS_INVALID =
      DiagnosticType.error("JSC_MSG_RESOLVE_FIRST_ARGUMENT_IS_INVALID",
          "A first argument of " + NEW_WITH
              + " is must be a function which return the dependencies.");

  static final DiagnosticType MESSAGE_BIND_FIRST_ARGUMENT_IS_INVALID =
      DiagnosticType.error("JSC_MSG_RESOLVE_FIRST_ARGUMENT_IS_INVALID",
          "A first argument of " + BINDER + " is must be a bindings.");

  static final DiagnosticType MESSAGE_BIND_SECOND_ARGUMENT_IS_INVALID =
      DiagnosticType.error("JSC_MSG_RESOLVE_FIRST_ARGUMENT_IS_INVALID",
          "A first argument of " + BINDER + " is must be an object literal.");

  static final DiagnosticType MESSAGE_BIND_OBJECT_LITERAL_MEMBER_IS_INVALID =
      DiagnosticType.error("JSC_MSG_RESOLVE_FIRST_ARGUMENT_IS_INVALID",
          "The properties of object literal of " + BINDER + " is must be a constructor.");

  static final DiagnosticType MESSAGE_BIND_OBJECT_LITERAL_TO_MEMBER_IS_INVALID =
      DiagnosticType.error("JSC_MSG_RESOLVE_FIRST_ARGUMENT_IS_INVALID",
          "The value of 'to : ...' key of object literal of " + BINDER + " is must be a constructor.");

  static final DiagnosticType MESSAGE_BIND_OBJECT_LITERAL_AS_MEMBER_IS_INVALID =
      DiagnosticType.error("JSC_MSG_RESOLVE_FIRST_ARGUMENT_IS_INVALID",
          "The value of 'as : ...' key of object literal of " + BINDER + " is must be a "
              + SINGLETON_SCOPES);

  static final DiagnosticType MESSAGE_BIND_OBJECT_LITERAL_INNER_PROPERTY_IS_INVALID =
      DiagnosticType.error("JSC_MSG_BIND_OBJECT_LITERAL_INNER_PROPERTY_IS_INVALID",
          "The keys of object literal of " + BINDER + " are only allowed 'to : ...' and 'as : ...'.");
  
  static final DiagnosticType MESSAGE_BIND_MUST_SPECIFY_CONSTRUCTOR =
      DiagnosticType.error("JSC_MSG_BIND_MUST_SPECIFY_CONSTRUCTOR",
          "The " + BINDER + " must sepcify the constructor.");

  private AbstractCompiler compiler;

  private FactoryInjectorInfo factoryInjectorInfo;


  public FactoryInjectorInfoCollector(AbstractCompiler compiler,
      FactoryInjectorInfo factoryInjectorInfo) {
    this.compiler = compiler;
    this.factoryInjectorInfo = factoryInjectorInfo;
  }


  public void process(Node externRoot, Node root) {
    NodeTraversal.traverse(compiler, root, new MarkerProcessCallback());
    NodeTraversal.traverse(compiler, root, new InjectionAliasFinder());
  }


  private final class TypeMarkerProcessor {
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
        }
      }

      if (name != null) {
        JSDocInfo info = NodeUtil.getBestJSDocInfo(n);
        typeInfo = new TypeInfo(name, n, info);
        factoryInjectorInfo.putTypeInfo(typeInfo);
      }
    }
  }


  private final class NewWtihMarkerProcessor {
    public void process(NodeTraversal t, Node n, Node parent) {
      if (isValidNewWithCall(t, n)) {
        factoryInjectorInfo.addNewWtihInfo(new NewWithInfo(n));
      }
    }


    private boolean isValidNewWithCall(NodeTraversal t, Node n) {
      Node firstChild = n.getFirstChild().getNext();
      if (firstChild == null || (!firstChild.isName() && !NodeUtil.isGet(firstChild))) {
        t.report(n, MESSAGE_NEW_WITH_FIRST_ARGUMENT_IS_INVALID);
        return false;
      }

      Node secondArg = firstChild.getNext();
      if (secondArg == null) {
        t.report(n, MESSAGE_NEW_WITH_SECOND_ARGUMENT_IS_INVALID);
        return false;
      }

      return true;
    }
  }


  private final class BinderMarkerProcessor {
    public void process(NodeTraversal t, Node n, Node parent, boolean isSingleton) {
      if (isValidBind(t, n)) {
        BinderInfo binderInfo = createBinderInfo(t, n, isSingleton);
        factoryInjectorInfo.addBinderInfo(binderInfo);
      }
    }


    private boolean isValidBind(NodeTraversal t, Node n) {
      Node firstArg = n.getFirstChild().getNext();
      if (firstArg == null) {
        t.report(n, MESSAGE_BIND_FIRST_ARGUMENT_IS_INVALID);
        return false;
      }

      Node secondArg = firstArg.getNext();
      if (secondArg == null || !secondArg.isObjectLit()) {
        t.report(n, MESSAGE_BIND_SECOND_ARGUMENT_IS_INVALID);
      }

      return true;
    }


    private BinderInfo createBinderInfo(NodeTraversal t, Node n, boolean isSingleton) {
      BinderInfo binderInfo = new BinderInfo(n);
      Node firstArg = n.getFirstChild().getNext();
      Node secondArg = firstArg.getNext();
      for (Node keyNode : secondArg.children()) {
        BindedKeyInfo keyInfo = new BindedKeyInfo(keyNode, keyNode.getString());
        Node value = keyNode.getFirstChild();
        if (value.isName() || NodeUtil.isGet(value)) {
          String name = value.getQualifiedName();
          if (name == null) {
            t.report(value, MESSAGE_BIND_OBJECT_LITERAL_MEMBER_IS_INVALID);
            return binderInfo;
          } else {
            BindedConstructorInfo ctorInfo = new BindedConstructorInfo(isSingleton, value, name);
            binderInfo.putBindingInfo(keyInfo, ctorInfo);
          }
        } else if (value.isObjectLit()) {
          boolean isSpecifiedSingleton = false;
          Node ctorNode = null;
          String ctorName = null;
          for (Node key : value.children()) {
            String keyStr = key.getString();
            if (keyStr.equals(KEY_TO)) {
              Node ctor = key.getFirstChild();
              if (!ctor.isName() && !NodeUtil.isGet(ctor)) {
                t.report(ctor, MESSAGE_BIND_OBJECT_LITERAL_TO_MEMBER_IS_INVALID);
                return binderInfo;
              }
              ctorNode = ctor;
              ctorName = ctor.getQualifiedName();
            } else if (keyStr.equals(KEY_AS)) {
              Node scope = key.getFirstChild();
              String qname = scope.getQualifiedName();
              if (!qname.equals(SINGLETON_SCOPES)) {
                t.report(scope, MESSAGE_BIND_OBJECT_LITERAL_AS_MEMBER_IS_INVALID);
                return binderInfo;
              }
              isSpecifiedSingleton = true;
            } else {
              t.report(key, MESSAGE_BIND_OBJECT_LITERAL_INNER_PROPERTY_IS_INVALID);
              return binderInfo;
            }
          }
          
          if (ctorNode != null && ctorName != null) {
            BindedConstructorInfo ctorInfo = new BindedConstructorInfo(isSingleton
                || isSpecifiedSingleton,
                ctorNode, ctorName);
            binderInfo.putBindingInfo(keyInfo, ctorInfo);
          } else {
            t.report(value, MESSAGE_BIND_MUST_SPECIFY_CONSTRUCTOR);
          }
        }
      }
      return binderInfo;
    }
  }


  private final class MarkerProcessCallback extends AbstractPostOrderCallback {

    private TypeMarkerProcessor typeMarkerProcessor = new TypeMarkerProcessor();

    private NewWtihMarkerProcessor newWithMarkerProcessor = new NewWtihMarkerProcessor();

    private BinderMarkerProcessor binderMarkerProcessor = new BinderMarkerProcessor();


    public void visit(NodeTraversal t, Node n, Node parent) {
      if (n.isCall()) {
        Node getprop = n.getFirstChild();
        if (getprop.isGetProp()) {
          String qname = getprop.getQualifiedName();
          if (qname != null) {
            if (qname.equals(NEW_WITH)) {
              newWithMarkerProcessor.process(t, n, parent);
            } else if (qname.equals(BINDER) || qname.equals(BINDER_SINGLETON)) {
              binderMarkerProcessor.process(t, n, parent, qname.equals(BINDER_SINGLETON));
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


    private void createAliasTypeInfoFrom(Node aliasPoint, List<TypeInfo> info, String aliasName,
        String name) {
      TypeInfo aliasInfo = new TypeInfo(name, aliasPoint, null);
      factoryInjectorInfo.putTypeInfo(aliasInfo);
      aliasInfo.setAlias();
      aliasInfo.setAliasName(aliasName);
    }


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
  }
}
