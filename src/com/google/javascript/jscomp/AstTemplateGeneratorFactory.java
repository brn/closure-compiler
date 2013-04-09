package com.google.javascript.jscomp;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.ImmutableMap;
import com.google.javascript.jscomp.CampInjectionInfo.ClassInfo;
import com.google.javascript.jscomp.CampInjectionInfo.InterceptorInfo;
import com.google.javascript.jscomp.CampInjectionInfo.PrototypeInfo;
import com.google.javascript.rhino.JSDocInfo;
import com.google.javascript.rhino.JSDocInfoBuilder;
import com.google.javascript.rhino.JSTypeExpression;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.Token;

public class AstTemplateGeneratorFactory {

  private CodingConvention convention;


  public AstTemplateGeneratorFactory(CodingConvention convention) {
    this.convention = convention;
  }


  public interface GeneratedAst {
    public Node getBlock();
  }


  public interface AstTemplateGenerator {
    public GeneratedAst generate(ClassInfo classInfo);
  }


  public enum TemplateGeneratorType {
    INTERCEPTOR
  }

  private final ImmutableMap<TemplateGeneratorType, AstTemplateGenerator> GENERATOR_MAP =
      new ImmutableMap.Builder<TemplateGeneratorType, AstTemplateGenerator>()
          .put(TemplateGeneratorType.INTERCEPTOR, new InterceptorCodeTemplateGenerator())
          .build();


  AstTemplateGenerator getGenerator(TemplateGeneratorType type) {
    return GENERATOR_MAP.get(type);
  }


  final class InterceptorCodeTemplateGenerator implements AstTemplateGenerator {
    private Map<ClassInfo, GeneratedAst> classInfoSet = Maps.newHashMap();

    private ClassInfo classInfo;


    private InterceptorCodeTemplateGenerator() {
    }


    public GeneratedAst generate(ClassInfo classInfo) {
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
      InterceptorCodeTemplateResult ret = new InterceptorCodeTemplateResult(block);
      this.classInfoSet.put(classInfo, ret);
      classInfo.rewriteClassName(functionName);
      classInfo.setAliasPoint(null);
      return ret;
    }


    private void declareSingleton(Node block, String functionName) {
      Node call = NodeUtil.newCallNode(
          NodeUtil.newQualifiedNameNode(convention, CampInjectionConsts.SINGLETON_CALL),
          Node.newString(Token.NAME, functionName));
      classInfo.setSingletonCallNode(call);
      block.addChildToBack(NodeUtil.newExpr(call));
    }


    private Node declareEnhancedFunction() {
      String suffix = CampInjectionProcessor.getValidVarName(CampInjectionProcessor
          .toLowerCase(classInfo.getClassName()));
      String functionName = String.format(CampInjectionConsts.ENHANCED_CONSTRUCTOR_FORMAT, suffix);
      Node paramList = new Node(Token.PARAM_LIST);
      this.setParameter(paramList);
      Node block = new Node(Token.BLOCK);
      Node function = new Node(Token.FUNCTION, Node.newString(Token.NAME, functionName), paramList,
          block);
      Node thisRef = new Node(Token.THIS);
      Node baseCall = NodeUtil.newCallNode(
          NodeUtil.newQualifiedNameNode(convention, CampInjectionConsts.GOOG_BASE), thisRef);
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
          CampInjectionConsts.GOOG_INHERITS));
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
                  + CampInjectionConsts.PROTOTYPE
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
          CampInjectionConsts.THIS_REFERENCE),
          Node.newString(Token.NAME, CampInjectionConsts.INTERCEPTOR_ARGUMENTS));

      ret.addChildToBack(className);
      ret.addChildToBack(methodName);
      ret.addChildToBack(innerCallNode);

      return ret;
    }


    private void setInterceptorRefNode(Node block) {
      block.addChildToBack(NodeUtil.newVarNode(CampInjectionConsts.INTERCEPTOR_ARGUMENTS,
          NodeUtil.newCallNode(
              NodeUtil.newQualifiedNameNode(convention, CampInjectionConsts.SLICE),
              Node.newString(Token.NAME, "arguments"))));
      block.addChildToBack(NodeUtil.newVarNode(CampInjectionConsts.THIS_REFERENCE, new Node(
          Token.THIS)));
    }
  }


  final class InterceptorCodeTemplateResult implements GeneratedAst {
    private Node block;


    private InterceptorCodeTemplateResult(Node block) {
      this.block = block;
    }


    public Node getBlock() {
      return this.block;
    }
  }

}
