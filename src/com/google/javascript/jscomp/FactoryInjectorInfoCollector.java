package com.google.javascript.jscomp;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.javascript.jscomp.FactoryInjectorInfo.CallType;
import com.google.javascript.jscomp.FactoryInjectorInfo.MethodInjectionInfo;
import com.google.javascript.jscomp.FactoryInjectorInfo.ResolvePoints;
import com.google.javascript.jscomp.FactoryInjectorInfo.TypeInfo;
import com.google.javascript.jscomp.NodeTraversal.AbstractPostOrderCallback;
import com.google.javascript.rhino.JSDocInfo;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.Token;

public class FactoryInjectorInfoCollector {

  private static final String MODULE_NAME = "camp.dependencies.injector";

  private static final String NEW_INSTANCE = MODULE_NAME + "." + "newInstance";

  private static final String GET_INSTANCE = MODULE_NAME + "." + "getInstance";

  private static final String DECL_INJECTIONS = "camp.dependencies.declInjections";

  private static final Pattern INJECTON_PARSE_REG = Pattern
      .compile("([a-zA-Z_$][\\w_$]*)(?:\\(([\\s\\S]+)\\))");

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

  private AbstractCompiler compiler;

  private FactoryInjectorInfo factoryInjectorInfo;


  public FactoryInjectorInfoCollector(AbstractCompiler compiler,
      FactoryInjectorInfo factoryInjectorInfo) {
    this.compiler = compiler;
    this.factoryInjectorInfo = factoryInjectorInfo;
  }


  public void process(Node externRoot, Node root) {
    NodeTraversal.traverse(compiler, root, new MarkerProcessCallback());
    bindInfo();
    NodeTraversal.traverse(compiler, root, new InjectionAliasFinder());
  }


  private void bindInfo() {
    for (TypeInfo typeInfo : factoryInjectorInfo.getTypeInfoMap().values()) {
      String name = typeInfo.getName();
      List<MethodInjectionInfo> methodInjectionInfoList =
          factoryInjectorInfo.getMethodInjectionInfo().get(name);
      if (methodInjectionInfoList.size() > 0) {
        typeInfo.setMethodInjectionList(methodInjectionInfoList);
      }
    }
  }


  private final class ResolveMarkerProcessor {
    public void process(NodeTraversal t, Node n, Node parent) {
      Node firstChild = n.getFirstChild();
      if (firstChild.isGetProp()) {
        String qname = firstChild.getQualifiedName();
        ResolvePoints resolvePoints = null;
        Node target = firstChild.getNext();
        CallType callType = null;
        if (qname != null && qname.equals(NEW_INSTANCE)) {
          if (isValidResolveCall(t, n, NEW_INSTANCE)) {
            callType = CallType.RESOLVE; 
          }
        } else if (qname != null && qname.equals(GET_INSTANCE)) {
          if (isValidResolveCall(t, n, GET_INSTANCE)) {
            callType = CallType.RESOLVE_ONCE;
          }
        }

        if (callType != null && target != null) {
          resolvePoints =
              new ResolvePoints(n, target.getQualifiedName(), target.getNext(), callType);
        }

        if (resolvePoints != null) {
          factoryInjectorInfo.addResolvePoints(resolvePoints);
        }
      }
    }


    private boolean isValidResolveCall(NodeTraversal t, Node resolveCall, String name) {
      Node maybeConstructor = resolveCall.getFirstChild().getNext();
      if (maybeConstructor == null
          || (!maybeConstructor.isName() && !NodeUtil.isGet(maybeConstructor))) {
        t.report(resolveCall, MESSAGE_RESOLVE_FIRST_ARGUMENT_IS_INVALID, name);
        return false;
      }

      Node bindings = maybeConstructor.getNext();
      if (bindings == null) {
        t.report(resolveCall, MESSAGE_RESOLVE_SECOND_ARGUMENT_IS_INVALID, name);
        return false;
      }
      return true;
    }
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
        typeInfo = new TypeInfo(name, n);
        factoryInjectorInfo.putTypeInfo(typeInfo);
      }
    }
  }


  private final class MethodInjectionMarkerProcessor {
    public void process(NodeTraversal t, Node n, Node parent) {
      Node constructorNameNode = n.getFirstChild().getNext();
      Node methodNameNode = constructorNameNode.getNext();
      String methodName = getSetterName(methodNameNode);
      String constructorName = getConstructorName(constructorNameNode);
      if (constructorName != null && methodName != null) {
        parseMethodInjectionInfo(t, methodNameNode, constructorNameNode, constructorName,
            methodName);
        Node setterNameNode = constructorNameNode.getNext();
        while (setterNameNode != null) {
          setterNameNode = setterNameNode.getNext();
          methodName = getSetterName(setterNameNode);
          if (methodName != null) {
            parseMethodInjectionInfo(t, setterNameNode, constructorNameNode, constructorName,
                methodName);
          } else {
            break;
          }
        }
        Node stmtBeginning = FactoryInjectorProcessor.getStatementBeginningNode(n);
        Preconditions.checkNotNull(stmtBeginning);
        stmtBeginning.detachFromParent();
        compiler.reportCodeChange();
      } else {
        if (constructorName == null) {
          t.report(n, MESSAGE_FIRST_ARGUMENT_OF_INJECT_IS_INVALID);
        }
        if (methodName == null) {
          t.report(n, MESSAGE_SECOND_ARGUMENT_OF_INJECT_IS_INVALID);
        }
      }
    }


    private void parseMethodInjectionInfo(
        NodeTraversal t,
        Node n,
        Node constructorNameNode,
        String constructorName,
        String methodName) {
      MethodInjectionInfo methodInjectionInfo = null;
      if (methodName.indexOf("(") > -1) {
        Matcher matcher = INJECTON_PARSE_REG.matcher(methodName);
        if (matcher.find()) {
          if (matcher.groupCount() == 2) {
            String paramStr = matcher.group(2);

            if (!Strings.isNullOrEmpty(paramStr)) {
              methodName = matcher.group(1).trim();
              String[] params = paramStr.split(",");
              List<String> parameterList = Lists.newArrayList();

              for (String param : params) {
                parameterList.add(param.trim());
              }

              if (methodName.equals("constructor")) {
                TypeInfo typeInfo = new TypeInfo(constructorName, n);
                factoryInjectorInfo.putTypeInfo(typeInfo);
              } else {
                methodInjectionInfo = new MethodInjectionInfo(constructorName, matcher.group(1)
                    .trim(), n);
                methodInjectionInfo.setParameterList(parameterList);
              }

              factoryInjectorInfo.putMethodInjectionInfo(methodInjectionInfo);

            } else {
              t.report(n, MESSAGE_INVALID_METHOD_INJECTION_SPECIFICATION, methodName);
            }
          } else {
            t.report(n, MESSAGE_INVALID_METHOD_INJECTION_SPECIFICATION, methodName);
          }
        } else {
          t.report(n, MESSAGE_INVALID_METHOD_INJECTION_SPECIFICATION, methodName);
        }
      } else {
        t.report(n, MESSAGE_INVALID_METHOD_INJECTION_SPECIFICATION, methodName);
      }
    }


    private String getConstructorName(Node constructorNameNode) {
      if (constructorNameNode != null) {
        String constructorName = constructorNameNode.getQualifiedName();
        if (!Strings.isNullOrEmpty(constructorName)) {
          return constructorName;
        }
      }
      return null;
    }


    private String getSetterName(Node setterNameNode) {
      if (setterNameNode != null && setterNameNode.isString()) {
        String name = setterNameNode.getString();
        if (!Strings.isNullOrEmpty(name)) {
          return name;
        }
      }
      return null;
    }
  }


  private final class MarkerProcessCallback extends AbstractPostOrderCallback {

    private ResolveMarkerProcessor resolveMarkerProcessor = new ResolveMarkerProcessor();

    private TypeMarkerProcessor typeMarkerProcessor = new TypeMarkerProcessor();

    private MethodInjectionMarkerProcessor methodInjectionMarkerProcessor = new MethodInjectionMarkerProcessor();


    public void visit(NodeTraversal t, Node n, Node parent) {
      if (n.isCall()) {
        Node getprop = n.getFirstChild();
        if (getprop.isGetProp()) {
          String qname = getprop.getQualifiedName();
          if (qname != null) {
            if (qname.equals(NEW_INSTANCE) || qname.equals(GET_INSTANCE)) {
              resolveMarkerProcessor.process(t, n, parent);
            } else if (qname.equals(DECL_INJECTIONS)) {
              methodInjectionMarkerProcessor.process(t, n, parent);
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
      TypeInfo aliasInfo = new TypeInfo(name, aliasPoint);
      factoryInjectorInfo.putTypeInfo(aliasInfo);
      aliasInfo.setMethodInjectionList(info.get(0).getMethodInjectionList());
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
