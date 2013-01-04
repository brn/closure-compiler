package jp.co.cyberagnet.camp.processor;

import java.util.HashMap;
import java.util.Collection;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.javascript.jscomp.AbstractCompiler;
import com.google.javascript.jscomp.CodingConvention.AssertionFunctionSpec;
import com.google.javascript.jscomp.CompilerPass;
import com.google.javascript.jscomp.DiagnosticType;
import com.google.javascript.jscomp.JSError;
import com.google.javascript.jscomp.NodeTraversal.AbstractPostOrderCallback;
import com.google.javascript.jscomp.NodeTraversal.AbstractScopedCallback;
import com.google.javascript.jscomp.NodeTraversal.Callback;
import com.google.javascript.jscomp.NodeTraversal;
import com.google.javascript.jscomp.NodeUtil;
import com.google.javascript.rhino.jstype.JSType;
import com.google.javascript.rhino.jstype.FunctionType;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.Token;
import com.google.javascript.rhino.JSDocInfo;
import com.google.javascript.rhino.JSDocInfoBuilder;
import com.google.javascript.rhino.JSTypeExpression;

import java.util.List;
import java.util.Set;

public final class InjectionProcessor implements CompilerPass {

  static final DiagnosticType MESSAGE_INJECT_ARGUMENT_MUST_BE_A_CONSTRUCTOR =
      DiagnosticType.error("JSC_MSG_INJECT_ARGUMENT_MUST_BE_A_CONSTRUCTOR", "The argument of camp.dependencies.injector.inject must be a constructor function which annotated as @constructor.");

  static final DiagnosticType MESSAGE_SETTER_INJECTION_TARGET_MUST_BE_A_STRING =
      DiagnosticType.error("JSC_MSG_SETTER_INJECTION_TARGET_MUST_BE_A_STRING", "The second argument and after of camp.dependencies.injector.inject must be a string which name of the prototype method.");

  static final DiagnosticType MESSAGE_BIND_ARGUMENT_MUST_BE_A_STRING =
      DiagnosticType.error("JSC_MSG_BIND_ARGUMENT_MUST_BE_A_STRING", "The first argument of camp.dependencies.injector.bind must be a string represent id.");

  static final DiagnosticType MESSAGE_BIND_SECOND_ARGUMENT_MUST_BE_SPECIFIED =
      DiagnosticType.error("JSC_MSG_BIND_ARGUMENT_MUST_BE_SPECIED", "The second argument of camp.dependencies.injector.bind must be specified.");

  static final DiagnosticType MESSAGE_METHOD_NEVER_DEFINED =
      DiagnosticType.error("JSC_MSG_METHOD_NEVER_DEFINED", "The method {0} never defined on {1}.");
	
  private final AbstractCompiler compiler;

  private static final String INJECTION_CALL = "camp.dependencies.injector.inject";

  private static final String SINGLETON_INJECTION_CALL = "camp.dependencies.injector.injectAsSingleton";

  private static final String INSTANIATION_CALL = "camp.dependencies.injector.createInstance";

  private static final String BINDING_CALL = "camp.dependencies.injector.bind";

  private static final String PROTOTYPE = "prototype";

  private static final String SINLETON_CALL = "getInstance";

  private ClassInjectionInfoRegistry classInjectionInfoRegistry = new ClassInjectionInfoRegistry();

  private BindingRegistry bindingRegistry = new BindingRegistry();
	
  private class SetterInjectionProcessor extends AbstractPostOrderCallback {
		
    private void processInjectionCall(NodeTraversal t, Node n) {
      Node firstChild = n.getFirstChild();
      if (firstChild.getType() == Token.GETPROP) {
        String fnName = firstChild.getQualifiedName();
        if (fnName != null) {
          boolean isSingleton = fnName.equals(SINGLETON_INJECTION_CALL);
          if (fnName.equals(INJECTION_CALL) || isSingleton) {
            registerInjections(t, n, firstChild.getNext(), isSingleton);
            detachInjectionCall(n);
            compiler.reportCodeChange();
          }
        }
      }
    }


    private void registerInjections(NodeTraversal t, Node n, Node nameNode, boolean isSingleton) {
      if (nameNode != null) {
        String className = nameNode.getQualifiedName();
        if (className != null && classInjectionInfoRegistry.hasInfo(className)) {
          ClassInjectionInfo classInjectionInfo = classInjectionInfoRegistry.getInfo(className);
          Node argumentsNode = nameNode.getNext();
          List<String> argumentsList = Lists.newArrayList();
          for (;argumentsNode != null; argumentsNode = argumentsNode.getNext()) {
            if (argumentsNode.isString()) {
              argumentsList.add(argumentsNode.getString());
            } else {
              t.report(argumentsNode, MESSAGE_SETTER_INJECTION_TARGET_MUST_BE_A_STRING, "");
            }
          }
          classInjectionInfo.setInjectionTargets(argumentsList, isSingleton);
        } else {
          t.report(n, MESSAGE_INJECT_ARGUMENT_MUST_BE_A_CONSTRUCTOR, "");
        }
      } else {
        t.report(n, MESSAGE_INJECT_ARGUMENT_MUST_BE_A_CONSTRUCTOR, "");
      }
    }

    private void detachInjectionCall(Node n) {
      Node node = n;
      while (!node.isExprResult() && node != null) {
        node = node.getParent();
      }
      if (node != null) {
        node.detachFromParent();
      }
    }

    @Override
    public void visit(NodeTraversal t, Node n, Node parent) {
      if (n.isCall()) {
        processInjectionCall(t, n);
      }
    }
  }

  private final class BindingProcessor extends AbstractPostOrderCallback {
    @Override
    public void visit(NodeTraversal t, Node n, Node parent) {
      if (n.getType() == Token.CALL) {
        processBiding(t, n);
      }
    }

    private void processBiding(NodeTraversal t, Node n) {
      Node child = n.getFirstChild();
      if (child.isGetProp() && BINDING_CALL.equals(child.getQualifiedName())) {
        Node bindingName = child.getNext();
        if (bindingName != null && bindingName.isString()) {
          String name = bindingName.getString();
          Node bindingValue = bindingName.getNext();
          if (bindingValue != null) {
            String value = bindingValue.getQualifiedName();
            if (value != null && classInjectionInfoRegistry.hasInfo(value)) {
              bindingRegistry.setClassBinding(name, bindingValue.cloneTree());
              detachBindingCall(n);
            } else {
              bindingRegistry.setPrimitiveBindings(name);
              replaceBindingCall(name, n, bindingValue.cloneTree());
            }
          } else {
            t.report(child, MESSAGE_BIND_SECOND_ARGUMENT_MUST_BE_SPECIFIED, "");
          }
        } else {
          t.report(child, MESSAGE_BIND_ARGUMENT_MUST_BE_A_STRING, "");
        }
      }
    }

    private void detachBindingCall(Node n) {
      Node node = n;
      while (!node.isExprResult() && node != null) {
        node = node.getParent();
      }
      if (node != null) {
        node.detachFromParent();
      }
      compiler.reportCodeChange();
    }

    private void replaceBindingCall(String name, Node n, Node bindingValue) {
      Node getprop = createPrimitiveArgument(name);
      Node assign = new Node(Token.ASSIGN, getprop, bindingValue);
      n.getParent().replaceChild(n, assign);
      compiler.reportCodeChange();
    }
  }

  private final class BindingRegistry {
    private HashMap<String, Integer> primitiveBindings = new HashMap<String, Integer>();
    private HashMap<String, Node> classBindings = new HashMap<String, Node>();

    public void setPrimitiveBindings(String name) {
      primitiveBindings.put(name, new Integer(1));
    }

    public void setClassBinding(String name, Node to) {
      classBindings.put(name, to);
    }

    public boolean hasPrimitiveBindings(String name) {
      return primitiveBindings.containsKey(name);
    }

    public Node getClassBindings(String name) {
      return classBindings.get(name);
    }

    public boolean isRegistered(String name) {
      return classBindings.containsKey(name) || primitiveBindings.containsKey(name);
    }
  }


  private final class InstaniationProcessor extends AbstractPostOrderCallback {
    @Override
    public void visit(NodeTraversal t, Node n, Node parent) {
      if (n.isCall()) {
        processInstaniation(t, n);
      }
    }

    private void processInstaniation(NodeTraversal t, Node n) {
      Node child = n.getFirstChild();
      if (INSTANIATION_CALL.equals(child.getQualifiedName())) {
        child = child.getNext();
        ClassInjectionInfo classInjectionInfo = classInjectionInfoRegistry.getInfo(child.getQualifiedName());
        if (classInjectionInfo != null) {
          Node newcall = createInstaniationCall(t, child.cloneTree(), classInjectionInfo);
          Node instaniationScope = createInstaniationScope(t, newcall, classInjectionInfo);
          n.getParent().replaceChild(n, instaniationScope);
          compiler.reportCodeChange();
        }
      }
    }

    private Node createInstaniationCall(NodeTraversal t, Node n, ClassInjectionInfo classInjectionInfo) {
      Node newcall;
      if (classInjectionInfo.isSingleton()) {
        Node getInstance = new Node(Token.GETPROP, n, Node.newString(SINLETON_CALL));
        newcall = new Node(Token.CALL, getInstance);
      } else {
        newcall = new Node(Token.NEW, n);
      }
      newcall.copyInformationFromForTree(n);
      List<String> targets = classInjectionInfo.getConstructorArguments();
      createArguments(t, targets, newcall);
      return newcall;
    }

    private Node createInstaniationScope(NodeTraversal t, Node newcall, ClassInjectionInfo classInjectionInfo) {
      List<String> targets = classInjectionInfo.getInjectionTargets();
      if (targets != null) {
        Node functionBody = new Node(Token.BLOCK);
        Node function = new Node(Token.FUNCTION,
                                 Node.newString(Token.NAME, ""),
                                 new Node(Token.PARAM_LIST),
                                 functionBody);
        Node anonymouseScope = new Node(Token.CALL, function);
        Node instance = Node.newString(Token.NAME, "instance");
        Node var = new Node(Token.VAR, instance);
        instance.addChildToBack(newcall);
        functionBody.addChildToBack(var);
        for (String target : targets) {
          Node method = Node.newString(target);
          Node getprop = new Node(Token.GETPROP, instance.cloneNode(), method);
          Node methodCall = new Node(Token.CALL, getprop);
          PrototypeMemberInfo prototypeMemberInfo = classInjectionInfo.getPrototypeMemberInfo(target);
          if (prototypeMemberInfo == null) {
            String superClass = classInjectionInfo.getSuperClass();
            if (superClass == null) {
              t.report(newcall, MESSAGE_METHOD_NEVER_DEFINED, target, classInjectionInfo.getClassName());
            }
            while (superClass != null) {
              ClassInjectionInfo backup = classInjectionInfo;
              classInjectionInfo = classInjectionInfoRegistry.getInfo(superClass);
              if (classInjectionInfo != null) {
                prototypeMemberInfo = classInjectionInfo.getPrototypeMemberInfo(target);
                if (prototypeMemberInfo != null) {
                  break;
                } else {
                  superClass = classInjectionInfo.getSuperClass();
                }
              } else {
                t.report(newcall, MESSAGE_METHOD_NEVER_DEFINED, target, backup.getClassName());
                break;
              }
            }
          }
          if (prototypeMemberInfo != null) {
            createArguments(t, prototypeMemberInfo.getArgumentsList(), methodCall);
            Node expr = new Node(Token.EXPR_RESULT, methodCall);
            functionBody.addChildToBack(expr);
          } else {
            t.report(newcall, MESSAGE_METHOD_NEVER_DEFINED, target, classInjectionInfo.getClassName());
          }
        }
        Node returnStatement = new Node(Token.RETURN, instance.cloneNode());
        functionBody.addChildToBack(returnStatement);
        return anonymouseScope;
      }
      return newcall;
    }

    private void createArguments(NodeTraversal t, List<String> targets, Node newcall) {
      if (targets != null) {
        for (String target : targets) {
          if (bindingRegistry.isRegistered(target)) {
            Node arg = null;
            if (bindingRegistry.hasPrimitiveBindings(target)) {
              arg = createPrimitiveArgument(target);
            } else {
              Node classEntity = bindingRegistry.getClassBindings(target);
              ClassInjectionInfo classInjectionInfo =
                  classInjectionInfoRegistry.getInfo(classEntity.getQualifiedName());
              if (classInjectionInfo != null) {
                arg = createInstaniationCall(t, classEntity, classInjectionInfo);
                arg = createInstaniationScope(t, arg, classInjectionInfo);
              }
            }
            if (arg != null) {
              newcall.addChildToBack(arg);
            }
          }
        }
      }
    }
  }


  private final class ConstructorInjectionProcessor extends AbstractScopedCallback {
    private ConstructorScopeChecker constructorScopeChecker;

    public ConstructorInjectionProcessor() {
      constructorScopeChecker = new ConstructorScopeChecker();
    }
		
    @Override
    public void visit(NodeTraversal t, Node n, Node parent) {
      JSDocInfo info = n.getJSDocInfo();
      if (info != null && info.isConstructor()) {
        Node fnNode = n.getFirstChild().getNext();
        System.out.println(n.toStringTree());
        FunctionType jsType = (FunctionType)fnNode.getJSType();
        if (jsType != null) {
          System.out.println(jsType.getPrototype());
        }
        ConstructorNamingProcessor namingProcessor = new ConstructorNamingProcessor(n, constructorScopeChecker);
        String name = namingProcessor.getName();
        Node function = namingProcessor.getNode();
        if (name != null) {
          List<String> parsedArgumentsList = new ConstructorArgumentsParser(function).parse();
          ClassInjectionInfo classInjectionInfo = new ClassInjectionInfo(name, parsedArgumentsList);
          JSTypeExpression exp = info.getBaseType();
          if (exp != null) {
            Node root = exp.getRoot();
            if (root != null && root.getChildCount() == 1) {
              classInjectionInfo.setSuperClass(root.getFirstChild().getString());
            }
          }
          classInjectionInfo.setJSDocInfo(info);
          classInjectionInfoRegistry.setInfo(classInjectionInfo);
        }
      } else if (n.isAssign() && shouldParsePrototype(n, classInjectionInfoRegistry)) {
        PrototypeMemberParser parser = new PrototypeMemberParser(n);
        parser.parse();
				
        PrototypeMemberInfo prototypeMemberInfo = new PrototypeMemberInfo(parser.getMethodName(),
                                                                          parser.getArgumentsList());				
        ClassInjectionInfo classInjectionInfo = classInjectionInfoRegistry.getInfo(parser.getClassName());

        if (classInjectionInfo != null) {
          classInjectionInfo.addPrototypeInfo(prototypeMemberInfo);
        }
      }
    }

    @Override
    public void enterScope(NodeTraversal t) {
      constructorScopeChecker.enterScope();
    }

    @Override
    public void exitScope(NodeTraversal t) {
      constructorScopeChecker.exitScope();
    }
  };
	
	
  private final class ClassInjectionInfoRegistry {
    private HashMap<String, ClassInjectionInfo> injectionInfos = new HashMap<String, ClassInjectionInfo>();

    public void setInfo(ClassInjectionInfo classInjectionInfo) {
      injectionInfos.put(classInjectionInfo.getClassName(), classInjectionInfo);
    }

    public ClassInjectionInfo getInfo(String className) {
      return injectionInfos.get(className);
    }

    public boolean hasInfo(String className) {
      return injectionInfos.containsKey(className);
    }
  }
	

  private final class ClassInjectionInfo {
    private String className;
    private boolean isSingleton;
    private List<String> argumentsList;
    private JSDocInfo constructorDocInfo;
    private List<String> injectionTargets;
    private HashMap<String, PrototypeMemberInfo> prototypeMemberInfos = new HashMap<String, PrototypeMemberInfo>();
    private String superClass;

    public ClassInjectionInfo(String className,
                              List<String> constructorArgumentsList) {
      this.className = className;
      this.argumentsList = constructorArgumentsList;
    }

    public void addPrototypeInfo(PrototypeMemberInfo prototypeMemberInfo) {
      this.prototypeMemberInfos.put(prototypeMemberInfo.getMethodName(),
                                    prototypeMemberInfo);
    }

    public void setJSDocInfo(JSDocInfo info) {
      constructorDocInfo = info;
    }

    public JSDocInfo getJSDocInfo() {
      return constructorDocInfo;
    }
    
    public List<String> getConstructorArguments() {
      return argumentsList;
    }
		
    public PrototypeMemberInfo getPrototypeMemberInfo(String methodName) {
      return this.prototypeMemberInfos.get(methodName);
    }
		
    public void setSuperClass(String superClass) {
      this.superClass = superClass;
    }

    public String getClassName() {
      return this.className;
    }

    public String getSuperClass() {
      return this.superClass;
    }

    public void setInjectionTargets(List<String> injectionTargets, boolean isSingleton) {
      this.injectionTargets = injectionTargets;
      this.isSingleton = isSingleton;
    }

    public List<String> getInjectionTargets() {
      return this.injectionTargets;
    }

    public boolean isSingleton() {
      return this.isSingleton;
    }
  }

  private final class PrototypeMemberInfo {
    String methodName;
    List<String> argumentsList;

    public PrototypeMemberInfo(String methodName, List<String> argumentsList) {
      this.methodName = methodName;
      this.argumentsList = argumentsList;
    }

    public String getMethodName() {
      return this.methodName;
    }

    public List<String> getArgumentsList() {
      return this.argumentsList;
    }
  }

  private final class ConstructorArgumentsParser {
    private Node constructor;
		
    public ConstructorArgumentsParser(Node constructor) {
      this.constructor = constructor;
    }

    public List<String> parse () {
      List<String> argumentsList = Lists.newArrayList();
      Node paramList = constructor.getFirstChild().getNext();
      if (paramList.getChildCount() > 0) {
        paramList = paramList.getFirstChild();
        for (Node n = paramList; n != null; n = n.getNext()) {
          argumentsList.add(n.getString());
        }
      }
      return argumentsList;
    }
  }


  private final class ConstructorNamingProcessor {
    private Node constructor;
    private Node function;
    private ConstructorScopeChecker scopeChecker;
		
    public ConstructorNamingProcessor(Node constructor,
                                      ConstructorScopeChecker scopeChecker) {
			
      this.constructor = constructor;
      this.scopeChecker = scopeChecker;
      if (isAssign()) {
        this.function = this.constructor.getChildAtIndex(1);
      } else if (isVarDeclaration()) {
        this.function = this.constructor.getFirstChild().getFirstChild();
      } else if (isFunctionDeclaration()) {
        this.function = this.constructor;
      } else {
        this.function = null;
      }
    }

    public String getName() {
      if (isAssign()) {
        return this.constructor.getFirstChild()
            .getQualifiedName();
      } else if (isVarDeclaration() && !this.scopeChecker.isInScope()) {
        return this.constructor.getFirstChild()
            .getString();
      } else if (isFunctionDeclaration() && !this.scopeChecker.isInScope()) {
        return this.function.getFirstChild().getString();
      }
      return null;
    }

    public Node getNode () {
      return this.function;
    }
		
    private boolean isAssign() {
      return this.constructor.isAssign();
    }

    private boolean isVarDeclaration() {
      return this.constructor.isVar();
    }

    private boolean isFunctionDeclaration() {
      return this.constructor.isFunction();
    }
  }


  private final class PrototypeMemberParser {
    private Node assignNode;
    private Node targetNode;
    private boolean isFunction;
    private String className;
    private String methodName;
    private List<String> argumentsList;

    public PrototypeMemberParser(Node assignNode) {
      this.assignNode = assignNode;
      this.targetNode = this.assignNode.getFirstChild().getNext();
      if (this.targetNode.isFunction()) {
        isFunction = true;
      } else {
        isFunction = false;
      }
    }

    public String getClassName() {
      return this.className;
    }

    public String getMethodName() {
      return this.methodName;
    }
    
    public List<String> getArgumentsList() {
      return this.argumentsList;
    }
		
    public void parse() {
      if (this.isFunction) {
        String qualifiedName = this.assignNode.getFirstChild().getQualifiedName();
        String className = parseMethodBelongedClassName(qualifiedName);
        String methodName = parseMethodName(qualifiedName);
        ConstructorArgumentsParser parser = new ConstructorArgumentsParser(this.targetNode);
        this.methodName = methodName;
        this.className = className;
        this.argumentsList = parser.parse();
      } else {
        
      }
    }
  }
	
	
  private final class ConstructorScopeChecker {
    private int scopeDepth = 0;

    public void enterScope() {
      scopeDepth++;
    }

    public void exitScope() {
      scopeDepth--;
    }

    public boolean isInScope() {
      return scopeDepth > 0;
    }
  }

  public InjectionProcessor(AbstractCompiler compiler) {
    this.compiler = compiler;
  }

  @Override
  public void process(Node externs, Node root) {
    System.out.println(root.toStringTree());
    NodeTraversal.traverse(compiler, root, new ConstructorInjectionProcessor());
    NodeTraversal.traverse(compiler, root, new SetterInjectionProcessor());
    NodeTraversal.traverse(compiler, root, new BindingProcessor());
    NodeTraversal.traverse(compiler, root, new InstaniationProcessor());
  }

  private static boolean shouldParsePrototype(Node assignNode, ClassInjectionInfoRegistry classInjectionInfoRegistry) {
    String name = parseMethodBelongedClassName(assignNode.getFirstChild().getQualifiedName());
    if (name != null && assignNode.getFirstChild().getNext().isFunction()) {
      return assignNode.isAssign() &&
          classInjectionInfoRegistry.hasInfo(name);
    }
    return false;
  }
		
  private static String parseMethodBelongedClassName(String qualifiedName) {
    if (qualifiedName != null) {
      int index = qualifiedName.indexOf(PROTOTYPE);
      if (index > -1) {
        return qualifiedName.substring(0, index - 1);
      }
    }
    return null;
  }

  private static String parseMethodName(String qualifiedName) {
    String[] splited = qualifiedName.split("\\.");
    return splited[splited.length - 1];
  }

  private static Node createPrimitiveArgument(String primitive) {
    Node camp = Node.newString(Token.NAME, "camp");
    Node dependencies = Node.newString("dependencies");
    Node injectionRegistry = Node.newString("injectionRegistry");
    Node name = Node.newString(primitive);
    Node getprop = new Node(Token.GETPROP, camp, dependencies);
    getprop = new Node(Token.GETPROP, getprop, injectionRegistry);
    return new Node(Token.GETPROP, getprop, name);
  }
}
