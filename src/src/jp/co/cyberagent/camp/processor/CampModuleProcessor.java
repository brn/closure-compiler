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

public final class CampModuleProcessor implements CompilerPass {

	static final DiagnosticType MESSAGE_MAIN_NOT_FUNCTION =
		DiagnosticType.error("JSC_MSG_MODULE_EXPORTS_Main must be a function.", "exported main must be a function of an application bootstrapper.");

	static final DiagnosticType MESSAGE_MAIN_ALREADY_FOUNDED =
		DiagnosticType.error("JSC_MSG_MAIN_ALREADY_FOUNDED.", "The function main allowed only one declaration per file. First defined {0}");

	static final DiagnosticType MESSAGE_MODULE_FIRST_ARGUMENT_NOT_VALID =
		DiagnosticType.error("JSC_MSG_MODULE_FIRST_ARGUMENT_NOT_VALID.", "The first argument of the camp.module must be a string expression of a module name.");

	static final DiagnosticType MESSAGE_MODULE_SECOND_ARGUMENT_NOT_VALID =
		DiagnosticType.error("JSC_MSG_MODULE_SECOND_ARGUMENT_NOT_VALID.", "The second argument of the camp.module must be a function which has an argument named <exports> only.");

	private final AbstractCompiler compiler;

	private final String exports = "exports";
	private final HashMap<String, Node> rootHolder = new HashMap<String, Node>();
	private final HashMap<String, PreCompileInfo> preCompInfo = new HashMap<String, PreCompileInfo>();


	private final class PreCompileInfo {
		private List<Node> useCalls;
		private List<Node> exportsList;
		private List<Node> moduleCalls;
		private List<JSDocInfo> jsdocs;
	
		public PreCompileInfo () {
			useCalls = Lists.newArrayList();
			exportsList = Lists.newArrayList();
			moduleCalls = Lists.newArrayList();
			jsdocs = Lists.newArrayList();
		}

		public List<Node> getUseCalls () {
			return useCalls;
		}

		public List<Node> getExportsList () {
			return exportsList;
		}

		public List<Node> getModuleCalls () {
			return moduleCalls;
		}

		public List<JSDocInfo> getJSDocs() {
			return jsdocs;
		}
	};
	
	
	private class ModuleResolver extends AbstractPostOrderCallback {
		private final String useCall = "camp.using";
		private final String moduleCall = "camp.module";
		private boolean isMainAlreadyFounded = false;
		private String firstDefined = "";
		private String mainPos = "";
		
		
		private Node getRoot (Node n) {
			Node parent = n.getParent();
			if (parent.getType() == Token.SCRIPT) {
				return parent;
			}
			while (parent != null) {
				if (parent.getType() == Token.SCRIPT) {
					return parent;
				} else {
					parent = parent.getParent();
				}
			}
			return parent;
		}

		private void initPreCompileInfo (String key, Node parent) {
			isMainAlreadyFounded = false;
			firstDefined = "";
			rootHolder.put(key, getRoot(parent));
			preCompInfo.put(key, new PreCompileInfo());
		}

		private boolean checkModuleCallIsValid (Node n, NodeTraversal t) {
			Node firstArg = n.getFirstChild().getNext();
			if (firstArg.getType() == Token.STRING) {
				Node secondArg = firstArg.getNext();
				if (secondArg.getType() == Token.FUNCTION) {
					if (secondArg.getChildCount() == 3 &&
						secondArg.getFirstChild().getNext().getType() == Token.PARAM_LIST &&
						secondArg.getFirstChild().getNext().getChildCount() == 1 &&
						secondArg.getFirstChild().getNext().getFirstChild().getString().equals("exports")) {
						return true;
					} else {
						t.report(secondArg, MESSAGE_MODULE_SECOND_ARGUMENT_NOT_VALID, "");
					}
				} else {
					t.report(secondArg, MESSAGE_MODULE_SECOND_ARGUMENT_NOT_VALID, "");
				}
			} else {
				t.report(firstArg, MESSAGE_MODULE_FIRST_ARGUMENT_NOT_VALID, "");
			}
			return false;
		}
    
		private void processModuleAndUsing (Node n, NodeTraversal t, List<Node> moduleCalls, List<Node> useCalls) {
			Node firstChild = n.getFirstChild();
			if (firstChild.getType() == Token.GETPROP) {
				if (firstChild.getFirstChild().getType() == Token.NAME) {
					String fnName = firstChild.getQualifiedName();
					if (fnName.equals(moduleCall)) {
						if (checkModuleCallIsValid(n, t)) {
							moduleCalls.add(n);
						}
					} else if (fnName.equals(useCall)) {
						useCalls.add(n);
					}
				}
			}
		}

		private void processMainAndExports (Node n, NodeTraversal t, List<Node> exportsList) {
			if (n.getType() == Token.GETPROP || n.getType() == Token.GETELEM) {
				Node firstChild = n.getFirstChild();
				if (firstChild.getType() == Token.NAME) {
					String propName = firstChild.getString();
					if (propName.equals(exports)) {
						if (firstChild.getNext().getString().equals("main")) {
							if (n.getNext().getType() != Token.FUNCTION) {
								t.report(n, MESSAGE_MAIN_NOT_FUNCTION, "");
							}
							if (isMainAlreadyFounded) {
								t.report(n, MESSAGE_MAIN_ALREADY_FOUNDED, firstDefined);
							}
							isMainAlreadyFounded = true;
							firstDefined = n.getSourceFileName() + " : " + n.getLineno();
						}
						exportsList.add(n);
					}
				}
			}
		}

		
		@Override
		public void visit(NodeTraversal t, Node n, Node parent) {
			String key = t.getSourceName();
			if (!preCompInfo.containsKey(key)) {
				initPreCompileInfo(key, parent);
			}
			PreCompileInfo preCompileInfo = preCompInfo.get(key);
			List<Node> useCalls = preCompileInfo.getUseCalls();
			List<Node> moduleCalls = preCompileInfo.getModuleCalls();
			List<Node> exportsList = preCompileInfo.getExportsList();
			List<JSDocInfo> jsdocList = preCompileInfo.getJSDocs();
			JSDocInfo info = n.getJSDocInfo();
			if (n.getType() == Token.CALL) {
				processModuleAndUsing(n, t, moduleCalls, useCalls);
			} else {
				processMainAndExports(n, t, exportsList);
			}
			if (info != null) {
				jsdocList.add(info);
			}
		}
	}


	public CampModuleProcessor(AbstractCompiler compiler) {
		this.compiler = compiler;
	}

	private Node createModuleQualifiedName (String[] moduleNames) {
		Node prop = null;
		for (String moduleName : moduleNames) {
			if (prop == null) {
				prop = Node.newString(Token.NAME, moduleName);
			} else {
				prop = new Node(Token.GETPROP, prop, Node.newString(moduleName));
			}
		}
		return prop;
	}
	
	@Override
	public void process(Node externs, Node root) {
		NodeTraversal.traverse(compiler, root, new ModuleResolver());
		for (String key : preCompInfo.keySet()) {
			PreCompileInfo preCompileInfo = preCompInfo.get(key);
			List<Node> moduleCalls = preCompileInfo.getModuleCalls();
			if (moduleCalls.size() > 0) {
				Node scriptBody = rootHolder.get(key);
				String moduleName = moduleCalls.get(0).getFirstChild().getNext().getString();
				String[] moduleNames = moduleName.split("\\.", 0);
				List<Node> exportsList = preCompileInfo.getExportsList();
				List<JSDocInfo> jsdocList = preCompileInfo.getJSDocs();
				processRequire(scriptBody, preCompileInfo.getUseCalls());
				processProvide(scriptBody, moduleCalls, exportsList);
				processExports(moduleNames, moduleName, scriptBody, exportsList, jsdocList);
			}
		}
	}

	private void processRequire (Node scriptBody, List<Node> useCalls) {
		int index = 0;
		Node firstChild = null;
		for (Node useCall : useCalls) {
			Node parent = useCall.getParent();
			Node firstArg = useCall.getFirstChild().getNext();
			if (firstArg.getType() == Token.STRING) {
				Node ns = Node.newString(firstArg.getString());
				Node name = Node.newString(Token.NAME, "goog");
				Node require = new Node(Token.GETPROP, name, Node.newString("require"));
				Node call = new Node(Token.CALL, require, ns);
				Node expResult = new Node(Token.EXPR_RESULT, call);
				call.copyInformationFromForTree(useCall);
				String[] moduleQualifiedName = firstArg.getString().split("\\.", 0);
				if (moduleQualifiedName.length > 0) {
					Node module = createModuleQualifiedName(moduleQualifiedName);
					if (parent.getType() == Token.EXPR_RESULT) {
						parent.copyInformationFromForTree(module);
						parent.getParent().replaceChild(parent, module);
					} else {
						useCall.copyInformationFromForTree(module);
						useCall.getParent().replaceChild(useCall, module);
					}
				} else {
					if (parent.getType() == Token.EXPR_RESULT) {
						parent.getParent().removeChild(parent);
					} else {
						useCall.detachFromParent();
					}
				}
				if (index == 0) {
					scriptBody.addChildToFront(expResult);
					index++;
				} else {
					scriptBody.addChildAfter(expResult, firstChild);
					index++;
				}
				firstChild = expResult;
			}
		}
		compiler.reportCodeChange();
	}

	private void processProvide (Node scriptBody, List<Node> moduleCalls, List<Node> exportsList) {
		for (Node module : moduleCalls) {
			Node parent = module.getParent();
			Node firstArg = module.getFirstChild().getNext();
			HashMap<String, Boolean> cache = new HashMap<String, Boolean>();
			if (firstArg.getType() == Token.STRING) {
				for (Node cls : exportsList) {
					String key = cls.getFirstChild().getNext().getString();
					if (key.equals("main")) {
						if (cls.getNext().getType() == Token.FUNCTION) {
							Node function = cls.getNext();
							function.detachFromParent();
							Node call = new Node(Token.CALL, function);
							cls.getParent().getParent().replaceChild(cls.getParent(), call);
						}
					} else if (!cache.containsKey(key)) {
						cache.put(key, true);
						Node ns = Node.newString(firstArg.getString() + "." + key);
						Node name = Node.newString(Token.NAME, "goog");
						Node require = new Node(Token.GETPROP, name, Node.newString("provide"));
						Node call = new Node(Token.CALL, require, ns);
						Node expResult = new Node(Token.EXPR_RESULT, call);
						call.copyInformationFromForTree(module);
						scriptBody.addChildToFront(expResult);
					}
				}
				Node nameNodes = module.getFirstChild().getFirstChild();
				nameNodes.setString("goog");
				nameNodes.getNext().setString("scope");
				firstArg.getNext().getFirstChild().getNext().removeChildren();
				firstArg.detachFromParent();
				compiler.reportCodeChange();
			}
		}
	}
  
	private void processExports (String[] moduleNames, String moduleName, Node scriptBody, List<Node> exportsList, List<JSDocInfo> jsdocList) {
		Node module = createModuleQualifiedName(moduleNames);
		for (Node exports : exportsList) {
			Node exportsToken = exports.getFirstChild();
			Node nameToken = exportsToken.getNext();
			Node cloned = module.cloneTree();
			Node child = new Node(exports.getType(), cloned, Node.newString(nameToken.getString()));
			nameToken.detachFromParent();
			child.useSourceInfoFromForTree(exports);
			child.setJSDocInfo(exports.getJSDocInfo());
			exports.getParent().replaceChild(exports, child);
		}
		for (JSDocInfo jsdoc : jsdocList) {
			for (Node type : jsdoc.getTypeNodes()) {
				fixTypeNode(type, moduleName);
			}
		}
		compiler.reportCodeChange();
	}

	private void fixTypeNode(Node typeNode, String moduleName) {
		if (typeNode.isString()) {
			String name = typeNode.getString();
			if (name.indexOf(exports + '.') != -1) {
				typeNode.setString(name.replaceAll(exports + '.', moduleName + '.'));
			}
		}

		for (Node child = typeNode.getFirstChild(); child != null;
			 child = child.getNext()) {
			fixTypeNode(child, moduleName);
		}
	}
}
