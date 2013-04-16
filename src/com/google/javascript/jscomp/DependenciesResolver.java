package com.google.javascript.jscomp;

import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.javascript.jscomp.AggressiveDIOptimizerInfo.BindingInfo;
import com.google.javascript.jscomp.AggressiveDIOptimizerInfo.ConstructorInfo;
import com.google.javascript.jscomp.AggressiveDIOptimizerInfo.InterceptorInfo;
import com.google.javascript.jscomp.AggressiveDIOptimizerInfo.ModuleInitializerInfo;
import com.google.javascript.jscomp.AggressiveDIOptimizerInfo.PrototypeInfo;
import com.google.javascript.rhino.IR;
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

  static final DiagnosticType MESSAGE_CLASS_DEFINITION_IS_DUPLICATED = DiagnosticType
      .error(
          "JSC_MSG_CLASS_DEFINITION_IS_DUPLICATED",
          "Compiler cannot inject dependencies because the constructor {0} definition is duplicated.");

  static final DiagnosticType MESSAGE_DEPENDENCIES_IS_CIRCULATED_OR_TOO_COMPLICATED = DiagnosticType
      .error(
          "JSC_MSG_CLASS_DEFINITION_IS_DUPLICATED",
          "Compiler cannot resolve dependencies because dependencies are circulated or too complicated.");

  private Map<String, BindingInfo> allBindingInfoMap;

  private Map<String, ConstructorInfo> clonedMap;

  private InterceptorAstBuilder interceptorBuilder;

  private ModuleInitializerInfo moduleInitializerInfo;

  private CodingConvention convention;

  private AbstractCompiler compiler;

  private int singletonId = 0;

  private int variableId = 0;

  private final ResolvingStackChecker resolvingStackChecker = new ResolvingStackChecker(100);


  public DependenciesResolver(
      Map<String, BindingInfo> allBindingInfoMap,
      Map<String, ConstructorInfo> clonedMap,
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


  Node makeProviderCall(BindingInfo bindingInfo) {
    resolvingStackChecker.reset();
    return doMakeProviderCall(bindingInfo, false);
  }


  private Node doMakeProviderCall(BindingInfo bindingInfo, boolean isPassProviderObject) {
    Node nameNode = NodeUtil.newQualifiedNameNode(convention, bindingInfo.getBindingAccessorName());
    Node call = NodeUtil.newCallNode(nameNode);
    Node function = bindingInfo.getBindedExpressionNode();
    Node paramList = NodeUtil.getFunctionParameters(function);

    addCallParameters(paramList, function, call);

    Node ret = null;
    if (isPassProviderObject) {
      ret = IR.function(IR.name(""),
          IR.paramList(),
          IR.block(IR.returnNode(call)));
    } else {
      ret = call;
    }

    if (!bindingInfo.isInConditional()) {
      return ret;
    } else {
      return IR.hook(nameNode.cloneTree(), ret, IR.nullNode());
    }
  }


  Node makeInstantiateExpression(ConstructorInfo constructorInfo) {
    resolvingStackChecker.reset();
    return doMakeInstantiateExpression(constructorInfo, constructorInfo.getBindingInfo());
  }


  private Node doMakeInstantiateExpression(
      ConstructorInfo constructorInfo,
      @Nullable BindingInfo bindingInfo) {
    if (constructorInfo.isDuplicated()) {
      report(constructorInfo.getConstructorNode(), MESSAGE_CLASS_DEFINITION_IS_DUPLICATED,
          constructorInfo.getClassName());
    }
    Node newCall = null;
    insertEnhancedConstructor(constructorInfo);

    if (constructorInfo.isSingleton()) {
      return makeSingletonCall(constructorInfo, bindingInfo);
    } else {
      newCall = (bindingInfo != null) ? makeBindingCall(constructorInfo, bindingInfo)
          : makeSimpleNewCall(constructorInfo);
      if (constructorInfo.getSetterList().size() > 0) {
        return this.makeMethodCallExpressionNode(newCall, constructorInfo);
      }
      return newCall;
    }
  }


  private Node resolveBinding(Node n, String bindingName) {
    resolvingStackChecker.push();

    if (resolvingStackChecker.isExceeded()) {
      report(n, MESSAGE_DEPENDENCIES_IS_CIRCULATED_OR_TOO_COMPLICATED);
      return IR.nullNode();
    }

    boolean isPassProviderObject = false;
    int index = bindingName.indexOf("Provider");

    if (index > -1) {
      isPassProviderObject = true;
      bindingName = bindingName.substring(0, index);
    }

    if (allBindingInfoMap.containsKey(bindingName)) {
      BindingInfo bindingInfo = allBindingInfoMap.get(bindingName);

      switch (bindingInfo.getBindingType()) {

      case TO:
        if (isPassProviderObject) {
          report(n, MESSAGE_BINDING_IS_NOT_A_PROVIDER, bindingName);
        }

        String name = bindingInfo.getBindedExpressionNode().getQualifiedName();
        ConstructorInfo info = clonedMap.get(name);

        if (info != null) {
          return doMakeInstantiateExpression(info, bindingInfo);
        } else {
          reportClassNotFound(n, name);
          break;
        }

      case TO_PROVIDER:
        return doMakeProviderCall(bindingInfo, isPassProviderObject);

      case TO_INSTANCE:
        if (isPassProviderObject) {
          report(n, MESSAGE_BINDING_IS_NOT_A_PROVIDER, bindingName);
          break;
        }
        if (!bindingInfo.isInConditional()) {
          return NodeUtil.newQualifiedNameNode(convention, bindingInfo.getBindingAccessorName());
        } else {
          Node getprop = NodeUtil.newQualifiedNameNode(convention,
              bindingInfo.getBindingAccessorName());
          return IR.or(getprop, IR.nullNode());
        }
      }
    } else {
      report(n, MESSAGE_BINDING_NOT_FOUND, bindingName);
    }
    return IR.nullNode();
  }


  private InterceptorCodeBlock makeEnhancedConstructor(ConstructorInfo constructorInfo) {
    if (!constructorInfo.isConstructorExtended()) {
      InterceptorCodeBlock result = interceptorBuilder.build(constructorInfo);
      return result;
    }
    return null;
  }


  private void insertEnhancedConstructor(ConstructorInfo constructorInfo) {
    if (constructorInfo.hasInterceptorFlag()) {
      InterceptorCodeBlock result = makeEnhancedConstructor(constructorInfo);
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


  private Node makeSimpleNewCall(ConstructorInfo constructorInfo) {
    Node newCall = IR.newNode(NodeUtil.newQualifiedNameNode(convention,
        constructorInfo.getClassName()));
    addCallParameters(constructorInfo.getParamList(), constructorInfo.getConstructorNode(), newCall);
    return newCall;
  }


  private Node makeBindingCall(ConstructorInfo constructorInfo, BindingInfo bindingInfo) {
    Node getprop = NodeUtil.newQualifiedNameNode(
        convention, bindingInfo.getBindingAccessorName());
    Node bindingCall = NodeUtil.newCallNode(getprop);
    addCallParameters(constructorInfo.getParamList(), constructorInfo.getConstructorNode(),
        bindingCall);
    if (compiler.getErrorManager().getWarningCount() > 0) {
      System.out.println(bindingCall.toStringTree());
    }

    if (!bindingInfo.isInConditional()) {
      return bindingCall;
    } else {
      return IR.hook(getprop.cloneTree(), bindingCall, IR.nullNode());
    }
  }


  private Node makeSingletonCall(ConstructorInfo constructorInfo, @Nullable BindingInfo bindingInfo) {
    insertEnhancedConstructor(constructorInfo);
    SingletonBuilder builder = new SingletonBuilder();
    if (constructorInfo.isEager()) {
      return builder.makeEagerSingleton(constructorInfo, bindingInfo);
    } else {
      return builder.makeLazySingleton(constructorInfo, bindingInfo);
    }
  }


  private Node makeMethodCallExpressionNode(Node newCall, ConstructorInfo constructorInfo) {
    Node function = moduleInitializerInfo.getModuleInitCall();
    Node block = NodeUtil.getFunctionBody(function);
    Node top = DIProcessor.getStatementBeginningNode(block.getFirstChild());
    Node instanceVar = null;

    Preconditions.checkNotNull(top);
    if (top.isExprResult() && top.getFirstChild().isAssign()) {
      instanceVar = top.getFirstChild();
    } else {
      Node var = NodeUtil.newVarNode("instance$" + variableId, null);
      var.copyInformationFromForTree(top);
      top.getParent().addChildBefore(var, top);
      instanceVar = var.getFirstChild().cloneNode();
      variableId++;
    }

    Node commaExp = this.makeCommaExpression(newCall, instanceVar, constructorInfo);
    return commaExp;
  }


  private Node makeCommaExpression(Node newCall, Node instanceVar, ConstructorInfo constructorInfo) {
    instanceVar = instanceVar.cloneTree();
    List<Node> expList = Lists.newArrayList(IR.assign(instanceVar.cloneTree(), newCall));
    for (String setterName : constructorInfo.getSetterList()) {
      PrototypeInfo prototypeInfo = constructorInfo.getPrototypeInfo(setterName);
      if (prototypeInfo != null) {
        Node setterCall = NodeUtil.newCallNode(NodeUtil.newQualifiedNameNode(convention,
            instanceVar.getQualifiedName() + "."
                + setterName));

        for (String param : prototypeInfo.getParamList()) {
          Node binding = resolveBinding(prototypeInfo.getFunction(), param);
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
          .addChildToBack(resolveBinding(n, param));
    }
  }


  private void addCallParameters(Node paramList, Node n, Node call) {
    Preconditions.checkArgument(paramList.isParamList());
    List<String> paramNameList = Lists.newArrayList();
    for (Node param : paramList.children()) {
      paramNameList.add(param.getString());
    }
    addCallParameters(paramNameList, n, call);
  }


  private final class ResolvingStackChecker {
    private int depth = 0;

    private int maxDepth;


    public ResolvingStackChecker(int maxDepth) {
      this.maxDepth = maxDepth;
    }


    public void push() {
      this.depth++;
    }


    public void reset() {
      this.depth = 0;
    }


    public boolean isExceeded() {
      return this.depth > this.maxDepth;
    }
  }


  private final class SingletonBuilder {

    private Node makeSingletonVariable() {
      Node instanceVar = Node.newString(Token.NAME, "singletonInstance" + singletonId);
      singletonId++;
      return instanceVar;
    }


    public Node makeLazySingleton(ConstructorInfo constructorInfo, @Nullable BindingInfo bindingInfo) {
      Node instanceVar;
      if (constructorInfo.getSingletonVariable() == null) {

        instanceVar = makeSingletonVariable();
        Node var = IR.var(instanceVar);
        Node function = moduleInitializerInfo.getModuleInitCall();
        Node block = NodeUtil.getFunctionBody(function);
        Node top = DIProcessor.getStatementBeginningNode(block.getFirstChild());
        Preconditions.checkNotNull(top);

        top.getParent().addChildBefore(var, top);
        constructorInfo.setSingletonVariable(instanceVar);

      } else {
        instanceVar = constructorInfo.getSingletonVariable();
      }

      Node newCall = bindingInfo != null ? makeBindingCall(constructorInfo, bindingInfo)
          : makeSimpleNewCall(constructorInfo);

      if (constructorInfo.getSetterList() != null) {
        newCall = makeCommaExpression(newCall, instanceVar, constructorInfo);
      }

      Node hook = IR.hook(instanceVar.cloneNode(), instanceVar.cloneNode(), newCall);
      return hook;
    }


    public Node makeEagerSingleton(ConstructorInfo constructorInfo,
        @Nullable BindingInfo bindingInfo) {
      if (constructorInfo.getSingletonVariable() == null) {
        Node instanceVar = makeSingletonVariable();
        Node var = IR.var(instanceVar);
        Node function = moduleInitializerInfo.getModuleInitCall();
        Node block = NodeUtil.getFunctionBody(function);
        block.addChildToFront(var);
        constructorInfo.setSingletonVariable(instanceVar);

        Node newCall = (bindingInfo != null) ? makeBindingCall(constructorInfo, bindingInfo)
            : makeSimpleNewCall(constructorInfo);

        if (constructorInfo.getSetterList() != null) {
          newCall = makeCommaExpression(newCall, instanceVar, constructorInfo);
        }

        Node assign = IR.assign(instanceVar.cloneNode(), newCall);
        Node expr = NodeUtil.newExpr(assign);
        expr.copyInformationFromForTree(block);
        block.addChildAfter(expr, block.getFirstChild());
        return instanceVar.cloneNode();
      } else {
        return constructorInfo.getSingletonVariable().cloneNode();
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
    private Map<ConstructorInfo, InterceptorCodeBlock> classInfoSet = Maps.newHashMap();

    private ConstructorInfo constructorInfo;

    private CodingConvention convention;


    public InterceptorAstBuilder(CodingConvention convention) {
      this.convention = convention;
    }


    public InterceptorCodeBlock build(ConstructorInfo constructorInfo) {
      this.constructorInfo = constructorInfo;
      if (this.classInfoSet.containsKey(constructorInfo)) {
        return this.classInfoSet.get(constructorInfo);
      }
      Node block = IR.block();
      Node function = this.declareEnhancedFunction();
      this.attachConstructorJSDocInfo(function);
      String functionName = function.getFirstChild().getString();
      block.addChildToBack(function);
      inherits(block, functionName);
      declarePrototype(block, functionName);
      InterceptorCodeBlock ret = new InterceptorCodeBlock(block);
      this.classInfoSet.put(constructorInfo, ret);
      constructorInfo.rewriteClassName(functionName);
      constructorInfo.setBindingInfo(null);
      constructorInfo.setAliasPoint(null);
      return ret;
    }


    private Node declareEnhancedFunction() {
      String suffix = DIProcessor.getValidVarName(DIProcessor
          .toLowerCase(constructorInfo.getClassName()));
      String functionName = String.format(DIConsts.ENHANCED_CONSTRUCTOR_FORMAT, suffix);
      Node paramList = IR.paramList();
      this.setParameter(paramList);
      Node block = IR.block();
      Node function = IR.function(IR.name(functionName), paramList,
          block);
      Node thisRef = IR.thisNode();
      Node baseCall = NodeUtil.newCallNode(
          NodeUtil.newQualifiedNameNode(convention, constructorInfo.getClassName() + ".call"),
          thisRef);
      setParameter(baseCall);
      block.addChildToBack(NodeUtil.newExpr(baseCall));
      block.copyInformationFromForTree(constructorInfo.getConstructorNode());
      constructorInfo.setConstructorNode(function);
      return function;
    }


    private void attachConstructorJSDocInfo(Node function) {
      JSDocInfo info = function.getJSDocInfo();
      JSDocInfoBuilder builder = new JSDocInfoBuilder(false);
      builder.recordConstructor();
      JSTypeExpression exp = new JSTypeExpression(new Node(Token.BANG,
          Node.newString(constructorInfo
              .getClassName())),
          constructorInfo.getConstructorNode().getSourceFileName());
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
      for (String name : constructorInfo.getParamList()) {
        paramListOrCall.addChildToBack(Node.newString(Token.NAME, name));
      }
    }


    private void inherits(Node block, String functionName) {
      Node call = NodeUtil.newCallNode(NodeUtil.newQualifiedNameNode(convention,
          DIConsts.GOOG_INHERITS));
      call.addChildToBack(Node.newString(Token.NAME, functionName));
      call.addChildToBack(NodeUtil.newQualifiedNameNode(convention, constructorInfo.getClassName()));
      block.addChildToBack(NodeUtil.newExpr(call));
    }


    private void declarePrototype(Node block, String functionName) {
      Map<String, PrototypeInfo> prototypeInfoMap = constructorInfo.getPrototypeInfoMap();
      for (PrototypeInfo prototypeInfo : prototypeInfoMap.values()) {
        Set<InterceptorInfo> interceptorInfoSet = prototypeInfo.getInterceptorInfoSet();
        if (interceptorInfoSet != null && interceptorInfoSet.size() > 0) {
          Node nameNode = NodeUtil.newQualifiedNameNode(convention,
              functionName
                  + "."
                  + DIConsts.PROTOTYPE
                  + "."
                  + prototypeInfo.getMethodName());
          Node node = IR.assign(nameNode, this.createIntercetporCall(constructorInfo,
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
        ConstructorInfo info,
        PrototypeInfo prototypeInfo,
        Set<InterceptorInfo> interceptorInfoSet) {

      Node functionNode = IR.function(IR.name(""), IR.paramList(), IR.block());
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
              IR.function(IR.name(""), IR.paramList(),
                  IR.block(IR.returnNode(call)));
          index++;
        }
      }

      block.addChildToBack(IR.returnNode(interceptorCall));
      return functionNode;
    }


    private Node createInterceptorCallNode(
        ConstructorInfo info,
        PrototypeInfo prototypeInfo,
        InterceptorInfo interceptorInfo,
        Node innerCallNode) {

      Node interceptorName = NodeUtil.newQualifiedNameNode(convention,
          interceptorInfo.getModuleName() + "." + interceptorInfo.getName());
      Node className = interceptorInfo.isClassNameAccess() ?
          IR.string(info.getClassName()) : IR.string("");
      Node methodName = interceptorInfo.isMethodNameAccess() ?
          IR.string(prototypeInfo.getMethodName()) : IR.string("");
      Node ret = NodeUtil.newCallNode(
          interceptorName,
          IR.name(DIConsts.THIS_REFERENCE),
          IR.name(DIConsts.INTERCEPTOR_ARGUMENTS));

      ret.addChildToBack(className);
      ret.addChildToBack(methodName);
      ret.addChildToBack(innerCallNode);

      if (!interceptorInfo.isInConditional()) {
        return ret;
      } else {
        return IR.hook(interceptorName.cloneTree(), ret,
            NodeUtil.newCallNode(
                IR.getprop(innerCallNode.cloneTree(), IR.string("apply")),
                IR.name(DIConsts.THIS_REFERENCE)));
      }
    }


    private void setInterceptorRefNode(Node block) {
      block.addChildToBack(NodeUtil.newVarNode(DIConsts.INTERCEPTOR_ARGUMENTS,
          NodeUtil.newCallNode(
              NodeUtil.newQualifiedNameNode(convention, DIConsts.SLICE),
              Node.newString(Token.NAME, "arguments"))));
      block.addChildToBack(NodeUtil.newVarNode(DIConsts.THIS_REFERENCE, IR.thisNode()));
    }
  }
}
