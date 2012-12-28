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
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.Token;
import com.google.javascript.rhino.JSDocInfo;
import com.google.javascript.rhino.JSDocInfoBuilder;
import com.google.javascript.rhino.JSTypeExpression;

import java.util.List;
import java.util.Set;

public final class InjectionProcessor implements CompilerPass {

	static final DiagnosticType MESSAGE_MAIN_NOT_FUNCTION =
		DiagnosticType.error("JSC_MSG_MODULE_EXPORTS_Main must be a function.", "exported main must be a function of an application bootstrapper.");

	private final AbstractCompiler compiler;

	private final String INJECTION_CALL = "camp.injector.Injector.inject";

	private final String SINGLETON_INJECTION_CALL = "camp.injector.Injector.injectSingleton";

	private final String FACTORY_NAME = "_factory";

	private final String PROTOTYPE = "prototype";

	private HashMap<String, List<String>> argumentsList = new HashMap<String, List<String>>();
	
	private class InjectionInliner extends AbstractPostOrderCallback {
		
		private void processInjectionCall(NodeTraversal t, Node n) {
			Node firstChild = n.getFirstChild();
			if (firstChild.getType() == Token.GETPROP) {
				String fnName = firstChild.getQualifiedName();
				if (fnName != null) {
					boolean isSingleton = fnName.equals(SINGLETON_INJECTION_CALL);
					if (fnName.equals(INJECTION_CALL) || isSingleton) {
						createSimpleFactory(n, isSingleton);
					}
				}
			}
		}


		private void createSimpleFactory(Node n, boolean isSingleton) {
			Node lhs = n.getFirstChild().getNext().cloneTree();
			List<String> args = argumentsList.get(n.getFirstChild().getNext().getQualifiedName());
			Node prototype = Node.newString(PROTOTYPE);
			Node factoryProto = new Node(Token.GETPROP, lhs, prototype);
			Node factoryProp = new Node(Token.GETPROP, factoryProto, Node.newString(FACTORY_NAME));
			Node factoryBody = createSimpleFactoryBody(lhs.cloneTree(), n.getParent(), isSingleton, args);
			Node assign = new Node(Token.ASSIGN, factoryProp, factoryBody);
			JSDocInfoBuilder builder = new JSDocInfoBuilder(false);
			builder.recordParameter("injections", new JSTypeExpression(new Node(Token.NAME, Node.newString("Object")), ""));
			builder.recordParameter("injector", new JSTypeExpression(new Node(Token.NAME, Node.newString("camp.injector.Injector")), ""));
			List<String> templateTypes = Lists.newArrayList();
			templateTypes.add("T");
			builder.recordTemplateTypeNames(templateTypes);
			builder.recordReturnType(new JSTypeExpression(new Node(Token.NAME, Node.newString(lhs.getQualifiedName())), ""));
			JSDocInfo info = builder.build(assign);
			assign.copyInformationFromForTree(n);
			assign.setJSDocInfo(info);
			n.getParent().replaceChild(n, assign);
			compiler.reportCodeChange();
		}


		private Node createSimpleFactoryBody(Node n, Node expr, boolean isSingleton, List<String> args) {
			Node function = new Node(Token.FUNCTION);
			Node constructorInvocation;
			if (isSingleton) {
				constructorInvocation = createSingletonInstanitionMethod(n, expr.getFirstChild(), args);
				createSingletonGetter(expr, n.cloneTree());
			} else {
				constructorInvocation = createNormalInstaniationMethod(n, expr.getFirstChild(), args);
			}
			constructorInvocation.copyInformationFromForTree(expr.getFirstChild());
			Node ret = new Node(Token.RETURN, constructorInvocation);
			Node block = new Node(Token.BLOCK, ret);
			Node param1 = Node.newString(Token.NAME, "injections");
			Node param2 = Node.newString(Token.NAME, "injector");
			Node paramList = new Node(Token.PARAM_LIST, param1);
			paramList.addChildToBack(param2);
			function.addChildToBack(Node.newString(Token.NAME, ""));
			function.addChildToBack(paramList);
			function.addChildToBack(block);
			return function;
		}


		private void createSingletonGetter(Node expr, Node target) {
			Node camp = Node.newString(Token.NAME, "camp");
			Node singleton = Node.newString("singleton");
			Node getprop = new Node(Token.GETPROP, camp, singleton);
			Node call = new Node(Token.CALL, getprop, target);
			Node targetExpr = new Node(Token.EXPR_RESULT, call);
			expr.getParent().addChildBefore(targetExpr, expr);
		}

		private Node createSingletonInstanitionMethod(Node n, Node call, List<String> args) {
			Node getInstanceProp = new Node(Token.GETPROP, n, Node.newString("getInstance"));
			Node getInstanceCall = new Node(Token.CALL, getInstanceProp);
			if (args != null && args.size() > 0) {
				createParameter(getInstanceCall, args);
			}
			return getInstanceCall;
		}

		private Node createNormalInstaniationMethod(Node n, Node call, List<String> args) {
			Node newCall = new Node(Token.NEW, n);
			if (args != null && args.size() > 0) {
				createParameter(newCall, args);
			}
			return newCall;
		}


		private void createParameter(Node invocation, List<String> paramList) {
			for (String param : paramList) {
				Node injections = Node.newString(Token.NAME, "injections");
				Node paramNode = Node.newString(param);
				Node getprop = new Node(Token.GETPROP, injections, paramNode);
				Node injector = Node.newString(Token.NAME, "injector");
				Node instaniate = Node.newString("createInstance");
				Node factory = new Node(Token.GETPROP, injector, instaniate);
				Node createInstanceCall = new Node(Token.CALL, factory);
				createInstanceCall.addChildToBack(getprop.cloneTree());
				invocation.addChildToBack(createInstanceCall);
			}
		}
		
		@Override
		public void visit(NodeTraversal t, Node n, Node parent) {
			if (n.getType() == Token.CALL) {
				processInjectionCall(t, n);
			}
		}
	}


	private final class ConstructorArgumentsProcessor extends AbstractScopedCallback {
		private ConstructorScopeChecker constructorScopeChecker;

		public ConstructorArgumentsProcessor() {
			constructorScopeChecker = new ConstructorScopeChecker();
		}
		
		@Override
		public void visit(NodeTraversal t, Node n, Node parent) {
			JSDocInfo info = n.getJSDocInfo();
			if (info != null && info.isConstructor()) {
				ConstructorNamingProcessor namingProcessor = new ConstructorNamingProcessor(n, constructorScopeChecker);
				String name = namingProcessor.getName();
				Node function = namingProcessor.getNode();
				if (name != null) {
					List<String> parsedArgumentsList = new ConstructorArgumentsParser(function).parse();
					argumentsList.put(name, parsedArgumentsList);
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
		NodeTraversal.traverse(compiler, root, new ConstructorArgumentsProcessor());
		NodeTraversal.traverse(compiler, root, new InjectionInliner());
	}
}
