package com.google.javascript.jscomp;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.javascript.jscomp.DIInfo.BindingInfo;
import com.google.javascript.jscomp.DIInfo.ClassInfo;
import com.google.javascript.jscomp.DIInfo.InterceptorInfo;
import com.google.javascript.jscomp.DIInfo.ModuleInitializerInfo;
import com.google.javascript.jscomp.DIInfo.PrototypeInfo;
import com.google.javascript.rhino.JSDocInfo;
import com.google.javascript.rhino.JSDocInfoBuilder;
import com.google.javascript.rhino.JSTypeExpression;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.Token;

public class DependenciesResolver {

  static final DiagnosticType MESSAGE_BINDING_IS_NOT_A_PROVIDER = DiagnosticType
      .warning("JSC_MSG_BINDING_IS_NOT_A_PROVIDER.",
          "The parameter is specified as provider but binding {0} is not a provider.");

  static final DiagnosticType MESSAGE_BINDING_NOT_FOUND = DiagnosticType.warning(
      "JSC_MSG_BINDING_NOT_FOUND",
      "Binding {0} is not found.");

  static final DiagnosticType MESSAGE_CLASS_NOT_FOUND = DiagnosticType.error(
      "MSG_CLASS_NOT_FOUND", "The class {0} is not defined.");

  private Map<String, Map<String, BindingInfo>> allBindingInfoMap;

  private Map<String, ClassInfo> clonedMap;

  private InterceptorAstBuilder interceptorBuilder;

  private ModuleInitializerInfo moduleInitializerInfo;

  private CodingConvention convention;

  private AbstractCompiler compiler;

  private int singletonId = 0;

  private int variableId = 0;


  public DependenciesResolver(
      Map<String, Map<String, BindingInfo>> allBindingInfoMap,
      Map<String, ClassInfo> clonedMap,
      ModuleInitializerInfo moduleInitializerInfo,
      AbstractCompiler compiler) {
    this.moduleInitializerInfo = moduleInitializerInfo;
    this.allBindingInfoMap = allBindingInfoMap;
    this.clonedMap = clonedMap;
    this.compiler = compiler;
    this.convention = this.compiler.getCodingConvention();
    this.interceptorBuilder = new InterceptorAstBuilder(convention);
  }


  void report(Node n, DiagnosticType message, String... arguments) {
    JSError error = JSError.make(n.getSourceFileName(),
        n, message, arguments);
    compiler.report(error);
  }


  void reportClassNotFound(Node n, String name) {
    report(n, MESSAGE_CLASS_NOT_FOUND, name);
  }


  Node makeProviderCall(BindingInfo bindingInfo, String moduleName,
      boolean isPassProviderObject) {
    String lowerClassName = DIProcessor.toLowerCase(DIProcessor
        .getValidVarName(moduleName));
    Node nameNode = NodeUtil.newQualifiedNameNode(convention, lowerClassName + "."
        + bindingInfo.getName());
    Node call = NodeUtil.newCallNode(nameNode);
    Node function = bindingInfo.getBindedExpressionNode();
    Node paramList = NodeUtil.getFunctionParameters(function);

    addCallParameters(paramList, function, call);

    if (isPassProviderObject) {
      return new Node(Token.FUNCTION, Node.newString(Token.NAME, ""),
          new Node(Token.PARAM_LIST),
          new Node(Token.BLOCK, new Node(Token.RETURN, call)));
    } else {
      return call;
    }
  }


  Node makeInstantiateExpression(ClassInfo classInfo) {
    Node newCall = null;
    this.insertEnhancedConstructor(classInfo);

    if (classInfo.isSingleton()) {
      return this.makeSingletonCall(classInfo);
    } else {
      newCall = this.makeSimpleNewCall(classInfo);
      if (classInfo.getSetterList().size() > 0) {
        return this.makeMethodCallExpressionNode(newCall, classInfo);
      }
      return newCall;
    }
  }


  private Node resolveBinding(Node n, String bindingName) {

    boolean isPassProviderObject = false;
    int index = bindingName.indexOf("Provider");

    if (index > -1) {
      isPassProviderObject = true;
      bindingName = bindingName.substring(0, index);
    }

    for (String className : allBindingInfoMap.keySet()) {
      Map<String, BindingInfo> bindingMap = allBindingInfoMap.get(className);

      if (bindingMap.containsKey(bindingName)) {
        BindingInfo bindingInfo = bindingMap.get(bindingName);

        switch (bindingInfo.getBindingType()) {

        case TO:
          if (isPassProviderObject) {
            report(n, MESSAGE_BINDING_IS_NOT_A_PROVIDER, bindingName);
          }

          String name = bindingInfo.getBindedExpressionNode().getQualifiedName();
          ClassInfo info = clonedMap.get(name);

          if (info != null) {
            return this.makeInstantiateExpression(info);
          } else {
            reportClassNotFound(n, name);
          }
          break;

        case TO_PROVIDER:
          System.out.println(bindingInfo.getName());
          return this.makeProviderCall(bindingInfo, className, isPassProviderObject);

        case TO_INSTANCE:
          if (isPassProviderObject) {
            report(n, MESSAGE_BINDING_IS_NOT_A_PROVIDER, bindingName);
          }

          String lowerClassName = DIProcessor.toLowerCase(DIProcessor
              .getValidVarName(className));

          return NodeUtil.newQualifiedNameNode(convention, lowerClassName + "." + bindingName);
        }
      }
    }

    report(n, MESSAGE_BINDING_NOT_FOUND, bindingName);
    return new Node(Token.NULL);
  }


  private InterceptorCodeBlock makeEnhancedConstructor(ClassInfo classInfo) {
    if (!classInfo.isConstructorExtended()) {
      InterceptorCodeBlock result = interceptorBuilder.build(classInfo);
      return result;
    }
    return null;
  }


  private void insertEnhancedConstructor(ClassInfo classInfo) {
    if (classInfo.hasInterceptorFlag()) {
      InterceptorCodeBlock result = makeEnhancedConstructor(classInfo);
      if (result != null) {
        Node function = moduleInitializerInfo.getModuleInitCall();
        Node block = NodeUtil.getFunctionBody(function);
        Node n = DIProcessor.getStatementBeginningNode(block.getFirstChild());
        if (n != null) {
          block = result.getBlock();
          n.getParent().addChildBefore(block, n);
          NodeUtil.tryMergeBlock(block);
        }
      }
    }
  }


  private Node makeSimpleNewCall(ClassInfo classInfo) {
    Node newCall = new Node(Token.NEW, NodeUtil.newQualifiedNameNode(convention,
        classInfo.getClassName()));
    this.addCallParameters(classInfo.getParamList(), classInfo.getConstructorNode(), newCall);
    return newCall;
  }


  private Node makeSingletonCall(ClassInfo classInfo) {
    this.insertEnhancedConstructor(classInfo);
    SingletonBuilder builder = new SingletonBuilder();
    if (classInfo.isEager()) {
      return builder.makeEagerSingleton(classInfo);
    } else {
      return builder.makeLazySingleton(classInfo);
    }
  }


  private Node makeMethodCallExpressionNode(Node newCall, ClassInfo classInfo) {
    Node function = moduleInitializerInfo.getModuleInitCall();
    Node block = NodeUtil.getFunctionBody(function);
    Node top = DIProcessor.getStatementBeginningNode(block.getFirstChild());
    Node instanceVar = null;

    Preconditions.checkNotNull(top);
    if (top.isExprResult() && top.getFirstChild().isAssign()) {
      instanceVar = top.getFirstChild();
    } else {
      Node var = new Node(Token.VAR, Node.newString(Token.NAME, "instance$"
          + variableId));
      var.copyInformationFromForTree(top);
      top.getParent().addChildBefore(var, top);
      instanceVar = var.getFirstChild().cloneNode();
      variableId++;
    }

    Node commaExp = this.makeCommaExpression(newCall, instanceVar, classInfo);
    return commaExp;
  }


  private Node makeCommaExpression(Node newCall, Node instanceVar, ClassInfo classInfo) {
    instanceVar = instanceVar.cloneTree();
    List<Node> expList = Lists.newArrayList(new Node(Token.ASSIGN, instanceVar.cloneTree(),
        newCall));
    for (String setterName : classInfo.getSetterList()) {
      PrototypeInfo prototypeInfo = classInfo.getPrototypeInfo(setterName);
      if (prototypeInfo != null) {
        Node setterCall = NodeUtil.newCallNode(NodeUtil.newQualifiedNameNode(convention,
            instanceVar.getQualifiedName() + "."
                + setterName));

        for (String param : prototypeInfo.getParamList()) {
          Node binding = this.resolveBinding(prototypeInfo.getFunction(), param);
          binding.copyInformationFromForTree(prototypeInfo.getFunction());
          setterCall.addChildToBack(binding);
        }

        expList.add(setterCall);
      }
    }
    expList.add(instanceVar.cloneTree());
    return DIProcessor.newCommaExpression(expList);
  }


  private void addCallParameters(List<String> paramList, Node n, Node call) {
    for (String param : paramList) {
      call
          .addChildToBack(this.resolveBinding(n, param));
    }
  }


  private void addCallParameters(Node paramList, Node n, Node call) {
    Preconditions.checkArgument(paramList.isParamList());
    List<String> paramNameList = Lists.newArrayList();
    for (Node param : paramList.children()) {
      paramNameList.add(param.getString());
    }
    this.addCallParameters(paramNameList, n, call);
  }


  private final class SingletonBuilder {

    private Node makeSingletonVariable() {
      Node instanceVar = Node.newString(Token.NAME, "singletonInstance" + singletonId);
      singletonId++;
      return instanceVar;
    }


    public Node makeLazySingleton(ClassInfo classInfo) {
      Node instanceVar;
      if (classInfo.getSingletonVariable() == null) {

        instanceVar = this.makeSingletonVariable();
        Node var = new Node(Token.VAR, instanceVar);
        Node function = moduleInitializerInfo.getModuleInitCall();
        Node block = NodeUtil.getFunctionBody(function);
        Node top = DIProcessor.getStatementBeginningNode(block.getFirstChild());
        Preconditions.checkNotNull(top);

        top.getParent().addChildBefore(var, top);
        classInfo.setSingletonVariable(instanceVar);

      } else {
        instanceVar = classInfo.getSingletonVariable();
      }

      Node newCall = makeSimpleNewCall(classInfo);

      if (classInfo.getSetterList() != null) {
        newCall = makeCommaExpression(newCall, instanceVar, classInfo);
      }

      Node hook = new Node(Token.HOOK, instanceVar.cloneNode(), instanceVar.cloneNode());
      hook.addChildToBack(newCall);
      return hook;
    }


    public Node makeEagerSingleton(ClassInfo classInfo) {
      if (classInfo.getSingletonVariable() == null) {
        Node instanceVar = this.makeSingletonVariable();
        Node var = new Node(Token.VAR, instanceVar);
        Node function = moduleInitializerInfo.getModuleInitCall();
        Node block = NodeUtil.getFunctionBody(function);
        block.addChildToFront(var);
        classInfo.setSingletonVariable(instanceVar);

        Node newCall = makeSimpleNewCall(classInfo);

        if (classInfo.getSetterList() != null) {
          newCall = makeCommaExpression(newCall, instanceVar, classInfo);
        }

        Node assign = new Node(Token.ASSIGN, instanceVar.cloneNode(), newCall);
        Node expr = NodeUtil.newExpr(assign);
        expr.copyInformationFromForTree(block);
        block.addChildAfter(expr, block.getFirstChild());
        return instanceVar.cloneNode();
      } else {
        return classInfo.getSingletonVariable().cloneNode();
      }
    }
  }


  static final class InterceptorCodeBlock {
    private Node block;


    private InterceptorCodeBlock(Node block) {
      this.block = block;
    }


    public Node getBlock() {
      return this.block;
    }
  }


  static final class InterceptorAstBuilder {
    private Map<ClassInfo, InterceptorCodeBlock> classInfoSet = Maps.newHashMap();

    private ClassInfo classInfo;

    private CodingConvention convention;


    public InterceptorAstBuilder(CodingConvention convention) {
      this.convention = convention;
    }


    public InterceptorCodeBlock build(ClassInfo classInfo) {
      this.classInfo = classInfo;
      if (this.classInfoSet.containsKey(classInfo)) {
        return this.classInfoSet.get(classInfo);
      }
      Node block = new Node(Token.BLOCK);
      Node function = this.declareEnhancedFunction();
      this.attachConstructorJSDocInfo(function);
      String functionName = function.getFirstChild().getString();
      block.addChildToBack(function);
      this.inherits(block, functionName);
      if (classInfo.isSingleton()) {
        this.declareSingleton(block, functionName);
      }
      this.declarePrototype(block, function, functionName);
      InterceptorCodeBlock ret = new InterceptorCodeBlock(block);
      this.classInfoSet.put(classInfo, ret);
      classInfo.rewriteClassName(functionName);
      classInfo.setAliasPoint(null);
      return ret;
    }


    private void declareSingleton(Node block, String functionName) {
      Node call = NodeUtil.newCallNode(
          NodeUtil.newQualifiedNameNode(convention, DIConsts.SINGLETON_CALL),
          Node.newString(Token.NAME, functionName));
      block.addChildToBack(NodeUtil.newExpr(call));
    }


    private Node declareEnhancedFunction() {
      String suffix = DIProcessor.getValidVarName(DIProcessor
          .toLowerCase(classInfo.getClassName()));
      String functionName = String.format(DIConsts.ENHANCED_CONSTRUCTOR_FORMAT, suffix);
      Node paramList = new Node(Token.PARAM_LIST);
      this.setParameter(paramList);
      Node block = new Node(Token.BLOCK);
      Node function = new Node(Token.FUNCTION, Node.newString(Token.NAME, functionName), paramList,
          block);
      Node thisRef = new Node(Token.THIS);
      Node baseCall = NodeUtil.newCallNode(
          NodeUtil.newQualifiedNameNode(convention, DIConsts.GOOG_BASE), thisRef);
      this.setParameter(baseCall);
      block.addChildToBack(NodeUtil.newExpr(baseCall));
      block.copyInformationFromForTree(classInfo.getConstructorNode());
      classInfo.setConstructorNode(function);
      return function;
    }


    private void attachConstructorJSDocInfo(Node function) {
      JSDocInfo info = function.getJSDocInfo();
      JSDocInfoBuilder builder = new JSDocInfoBuilder(false);
      builder.recordConstructor();
      JSTypeExpression exp = new JSTypeExpression(new Node(Token.BANG, Node.newString(classInfo
          .getClassName())),
          classInfo.getConstructorNode().getSourceFileName());
      builder.recordBaseType(exp);

      if (info != null) {
        for (String paramName : info.getParameterNames()) {
          JSTypeExpression paramType = info.getParameterType(paramName);
          if (paramType != null) {
            builder.recordParameter(paramName, paramType);
          }
        }
      }

      function.setJSDocInfo(builder.build(function));
    }


    private void setParameter(Node paramListOrCall) {
      for (String name : classInfo.getParamList()) {
        paramListOrCall.addChildToBack(Node.newString(Token.NAME, name));
      }
    }


    private void inherits(Node block, String functionName) {
      Node call = NodeUtil.newCallNode(NodeUtil.newQualifiedNameNode(convention,
          DIConsts.GOOG_INHERITS));
      call.addChildToBack(Node.newString(Token.NAME, functionName));
      call.addChildToBack(NodeUtil.newQualifiedNameNode(convention, classInfo.getClassName()));
      block.addChildToBack(NodeUtil.newExpr(call));
    }


    private void declarePrototype(Node block, Node function, String functionName) {
      Map<String, PrototypeInfo> prototypeInfoMap = classInfo.getPrototypeInfoMap();
      for (PrototypeInfo prototypeInfo : prototypeInfoMap.values()) {
        Set<InterceptorInfo> interceptorInfoSet = prototypeInfo.getInterceptorInfoSet();
        if (interceptorInfoSet != null && interceptorInfoSet.size() > 0) {
          Node nameNode = NodeUtil.newQualifiedNameNode(convention,
              functionName
                  + "."
                  + DIConsts.PROTOTYPE
                  + "."
                  + prototypeInfo.getMethodName());
          Node node = new Node(Token.ASSIGN, nameNode, this.createIntercetporCall(classInfo,
              prototypeInfo, interceptorInfoSet));
          this.attachMethodJSDocInfo(node);
          Node expr = NodeUtil.newExpr(node);
          expr.copyInformationFromForTree(prototypeInfo.getFunction());
          block.addChildToBack(expr);
        }
      }
    }


    private void attachMethodJSDocInfo(Node assign) {
      JSDocInfoBuilder builder = new JSDocInfoBuilder(false);
      builder.recordOverride();
      assign.setJSDocInfo(builder.build(assign));
    }


    private Node createIntercetporCall(
        ClassInfo info,
        PrototypeInfo prototypeInfo,
        Set<InterceptorInfo> interceptorInfoSet) {

      Node functionNode = new Node(Token.FUNCTION, Node.newString(Token.NAME, ""), new Node(
          Token.PARAM_LIST),
          new Node(Token.BLOCK));
      Node block = NodeUtil.getFunctionBody(functionNode);
      Node paramList = NodeUtil.getFunctionParameters(functionNode);

      for (String paramName : prototypeInfo.getParamList()) {
        paramList.addChildToBack(Node.newString(Token.NAME, paramName));
      }

      Node interceptorCall;
      this.setInterceptorRefNode(block);

      if (interceptorInfoSet.size() == 1) {
        Node prototypeMethodAccessorNode = NodeUtil.newQualifiedNameNode(convention,
            String.format("%s.prototype.%s", info.getClassName(), prototypeInfo.getMethodName()));

        interceptorCall = createInterceptorCallNode(info, prototypeInfo,
            interceptorInfoSet.iterator().next(), prototypeMethodAccessorNode);
      } else {

        List<InterceptorInfo> copied = Lists.newArrayList(interceptorInfoSet);
        String methodName = String.format("%s.prototype.%s", info.getClassName(),
            prototypeInfo.getMethodName());
        interceptorCall = NodeUtil.newQualifiedNameNode(convention, methodName);
        int index = 0;
        for (InterceptorInfo interceptorInfo : copied) {
          Node call = createInterceptorCallNode(
              info,
              prototypeInfo,
              interceptorInfo,
              interceptorCall
              );

          interceptorCall = (index == copied.size() - 1) ?
              call :
              new Node(Token.FUNCTION, Node.newString(Token.NAME, ""), new Node(Token.PARAM_LIST),
                  new Node(Token.BLOCK, new Node(Token.RETURN, call)));
          index++;
        }
      }

      block.addChildToBack(new Node(Token.RETURN, interceptorCall));
      return functionNode;
    }


    private Node createInterceptorCallNode(
        ClassInfo info,
        PrototypeInfo prototypeInfo,
        InterceptorInfo interceptorInfo,
        Node innerCallNode) {

      Node interceptorName = NodeUtil.newQualifiedNameNode(convention,
          interceptorInfo.getModuleName() + "." + interceptorInfo.getName());
      Node className = interceptorInfo.isClassNameAccess() ? Node.newString(info.getClassName())
          : Node.newString("");
      Node methodName = interceptorInfo.isMethodNameAccess() ? Node.newString(prototypeInfo
          .getMethodName()) : Node.newString("");
      Node ret = NodeUtil.newCallNode(interceptorName, Node.newString(Token.NAME,
          DIConsts.THIS_REFERENCE),
          Node.newString(Token.NAME, DIConsts.INTERCEPTOR_ARGUMENTS));

      ret.addChildToBack(className);
      ret.addChildToBack(methodName);
      ret.addChildToBack(innerCallNode);

      return ret;
    }


    private void setInterceptorRefNode(Node block) {
      block.addChildToBack(NodeUtil.newVarNode(DIConsts.INTERCEPTOR_ARGUMENTS,
          NodeUtil.newCallNode(
              NodeUtil.newQualifiedNameNode(convention, DIConsts.SLICE),
              Node.newString(Token.NAME, "arguments"))));
      block.addChildToBack(NodeUtil.newVarNode(DIConsts.THIS_REFERENCE, new Node(
          Token.THIS)));
    }
  }
}
