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
				if (fnName.equals(INJECTION_CALL)) {
					if (n.getChildCount() == 2) {
						createSimpleFactory(n);
					}
				} else if (fnName.equals(SINGLETON_INJECTION_CALL)) {
					if (n.getChildCount() == 2) {
						createSimpleSingletonFactory(n);
					}
				}
			}
		}


		private void createSimpleFactory(Node n) {
			Node lhs = n.getFirstChild().getNext().cloneTree();
			Node prototype = Node.newString(PROTOTYPE);
			Node factoryProto = new Node(Token.GETPROP, lhs, prototype);
			Node factoryProp = new Node(Token.GETPROP, factoryProto, Node.newString(FACTORY_NAME));
			Node factoryBody = createSimpleFactoryBody(lhs.cloneTree());
			Node assign = new Node(Token.ASSIGN, factoryProp, factoryBody);
			assign.copyInformationFromForTree(n);
			n.getParent().replaceChild(n, assign);
			compiler.reportCodeChange();
		}


		private Node createSimpleFactoryBody(Node n) {
			Node function = new Node(Token.FUNCTION);
			Node newCall = new Node(Token.NEW, n);
			Node ret = new Node(Token.RETURN, newCall);
			Node block = new Node(Token.BLOCK, ret);
			Node param = Node.newString(Token.NAME, "injections");
			function.addChildToBack(Node.newString(Token.NAME, ""));
			function.addChildToBack(new Node(Token.PARAM_LIST, param));
			function.addChildToBack(block);
			return function;
		}


		private void createSimpleSingletonFactory(Node n) {
			Node lhs = n.getFirstChild().getNext().cloneTree();
			Node prototype = Node.newString(PROTOTYPE);
			Node factoryProto = new Node(Token.GETPROP, lhs, prototype);
			Node factoryProp = new Node(Token.GETPROP, factoryProto, Node.newString(FACTORY_NAME));
			Node factoryBody = createSimpleSingletonFactoryBody(lhs.cloneTree());
			Node assign = new Node(Token.ASSIGN, factoryProp, factoryBody);
			assign.copyInformationFromForTree(n);
			n.getParent().replaceChild(n, assign);
			createSingletonGetter(assign, lhs.cloneTree());
			compiler.reportCodeChange();
		}

		private void createSingletonGetter(Node n, Node target) {
			Node expr = n.getParent();
			Node camp = Node.newString(Token.NAME, "camp");
			Node singleton = Node.newString("singleton");
			Node getprop = new Node(Token.GETPROP, camp, singleton);
			Node call = new Node(Token.CALL, getprop, target);
			Node targetExpr = new Node(Token.EXPR_RESULT, call);
			expr.getParent().addChildBefore(targetExpr, expr);
		}

		private Node createSimpleSingletonFactoryBody(Node n) {
			Node function = new Node(Token.FUNCTION);
			Node prototype = Node.newString(PROTOTYPE);
			Node getInstanceProto = new Node(Token.GETPROP, n, prototype);
			Node getInstanceProp = new Node(Token.GETPROP, getInstanceProto, Node.newString("getInstance"));
			Node getInstanceCall = new Node(Token.CALL, getInstanceProp);
			Node ret = new Node(Token.RETURN, getInstanceCall);
			Node block = new Node(Token.BLOCK, ret);
			Node param = Node.newString(Token.NAME, "injections");
			function.addChildToBack(Node.newString(Token.NAME, ""));
			function.addChildToBack(new Node(Token.PARAM_LIST, param));
			function.addChildToBack(block);
			return function;
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
		System.out.println(root.toStringTree());
	}
}
