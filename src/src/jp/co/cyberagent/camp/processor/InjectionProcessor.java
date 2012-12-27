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
  
	private class InjectionInliner extends AbstractPostOrderCallback {
		
		private void processInjectionCall(NodeTraversal t, Node n) {
			Node firstChild = n.getFirstChild();
			if (firstChild.getType() == Token.GETPROP) {
				String fnName = firstChild.getQualifiedName();
				boolean isSingleton = fnName.equals(SINGLETON_INJECTION_CALL);
				if (fnName.equals(INJECTION_CALL) || isSingleton) {
					createSimpleFactory(n, isSingleton);
				}
			}
		}


		private void createSimpleFactory(Node n, boolean isSingleton) {
			Node lhs = n.getFirstChild().getNext().cloneTree();
			Node prototype = Node.newString(PROTOTYPE);
			Node factoryProto = new Node(Token.GETPROP, lhs, prototype);
			Node factoryProp = new Node(Token.GETPROP, factoryProto, Node.newString(FACTORY_NAME));
			Node factoryBody = createSimpleFactoryBody(lhs.cloneTree(), n.getParent(), isSingleton);
			Node assign = new Node(Token.ASSIGN, factoryProp, factoryBody);
			JSDocInfoBuilder builder = new JSDocInfoBuilder(false);
			builder.recordParameter("injection", new JSTypeExpression(new Node(Token.NAME, Node.newString("T")), ""));
			builder.recordParameter("injector", new JSTypeExpression(new Node(Token.NAME, Node.newString("camp.injector.Injector")), ""));
			List<String> templateTypes = Lists.newArrayList();
			templateTypes.add("T");
			builder.recordTemplateTypeNames(templateTypes);
			builder.recordReturnType(new JSTypeExpression(new Node(Token.NAME, Node.newString(lhs.getQualifiedName())), ""));
			JSDocInfo info = builder.build(assign);
			assign.copyInformationFromForTree(n);
			n.getParent().replaceChild(n, assign);
			compiler.reportCodeChange();
		}


		private Node createSimpleFactoryBody(Node n, Node expr, boolean isSingleton) {
			Node function = new Node(Token.FUNCTION);
			Node constructorInvocation;
			if (isSingleton) {
				constructorInvocation = createSingletonInstanitionMethod(n, expr.getFirstChild());
				createSingletonGetter(expr, n.cloneTree());
			} else {
				constructorInvocation = createNormalInstaniationMethod(n, expr.getFirstChild());
			}
			constructorInvocation.copyInformationFromForTree(expr.getFirstChild());
			System.out.println(constructorInvocation.toStringTree());
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

		private Node createSingletonInstanitionMethod(Node n, Node call) {
			Node getInstanceProp = new Node(Token.GETPROP, n, Node.newString("getInstance"));
			Node getInstanceCall = new Node(Token.CALL, getInstanceProp);
			if (call.getChildCount() > 2) {
				createParameter(getInstanceCall, call);
			}
			return getInstanceCall;
		}

		private Node createNormalInstaniationMethod(Node n, Node call) {
			Node newCall = new Node(Token.NEW, n);
			if (call.getChildCount() > 2) {
				createParameter(newCall, call);
			}
			return newCall;
		}


		private void createParameter(Node invocation, Node call) {
			Node params = call.getFirstChild().getNext().getNext();
			List<String> paramList = Lists.newArrayList();
			while (params != null) {
				if (params.getType() == Token.STRING) {
					paramList.add(params.getString());
				}
				params = params.getNext();
			}
			for (String param : paramList) {
				if (Character.isUpperCase(param.toCharArray()[0])) {
					Node injector = Node.newString(Token.NAME, "injector");
					Node instaniate = Node.newString("createInstance");
					Node getprop = new Node(Token.GETPROP, injector, instaniate);
					Node createInstanceCall = new Node(Token.CALL, getprop);
					Node injections = Node.newString(Token.NAME, "injections");
					Node paramNode = Node.newString(param);
					Node injection = new Node(Token.GETPROP, injections, paramNode);
					createInstanceCall.addChildToBack(injection);
					invocation.addChildToBack(createInstanceCall);
				} else {
					Node injections = Node.newString(Token.NAME, "injections");
					Node paramNode = Node.newString(param);
					Node getprop = new Node(Token.GETPROP, injections, paramNode);
					invocation.addChildToBack(getprop);
				}
			}
		}
		
		@Override
		public void visit(NodeTraversal t, Node n, Node parent) {
			if (n.getType() == Token.CALL) {
				processInjectionCall(t, n);
			}
		}
	}


	public InjectionProcessor(AbstractCompiler compiler) {
		this.compiler = compiler;
	}
	
	@Override
	public void process(Node externs, Node root) {
		NodeTraversal.traverse(compiler, root, new InjectionInliner());
		//System.out.println(root.toStringTree());
	}
}
