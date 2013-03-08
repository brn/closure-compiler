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
import jp.co.cyberagnet.camp.processor.CampModuleTransformInfo;

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

	private final String EXPORTS = "exports";
	private final HashMap<String, Node> rootHolder = new HashMap<String, Node>();
	private final HashMap<String, CampModuleTransformInfo> campModuleTransformInfoMap = new HashMap<String, CampModuleTransformInfo>();
	
	
	private class ModuleResolver extends AbstractPostOrderCallback {
		private final String CAMP_USING_CALL = "camp.using";
		private final String CAMP_MODULE_CALL = "camp.module";
		private boolean isMainAlreadyFounded = false;
		private String firstDefined = "";
		private String mainPos = "";
		
		
		private Node getRoot(Node n) {
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

		
		private void initCampModuleTransformInfo(String key, Node parent) {
			isMainAlreadyFounded = false;
			firstDefined = "";
			rootHolder.put(key, getRoot(parent));
			campModuleTransformInfoMap.put(key, new CampModuleTransformInfo());
		}

		
		private boolean checkModuleCallIsValid(Node n, NodeTraversal t) {
			Node firstArg = n.getFirstChild().getNext();
			if (firstArg.getType() == Token.STRING) {
				Node secondArg = firstArg.getNext();
				if (secondArg.getType() == Token.FUNCTION) {
					if (secondArg.getChildCount() == 3 &&
						secondArg.getFirstChild().getNext().getType() == Token.PARAM_LIST &&
						secondArg.getFirstChild().getNext().getChildCount() == 1 &&
						secondArg.getFirstChild().getNext().getFirstChild().getString().equals(EXPORTS)) {
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
    
		private void processModuleAndUsing(Node n, NodeTraversal t, List<Node> moduleCalls, List<Node> usingCalls) {
			Node firstChild = n.getFirstChild();
			if (firstChild.getType() == Token.GETPROP) {
				if (firstChild.getFirstChild().getType() == Token.NAME) {
					String fnName = firstChild.getQualifiedName();
					if (fnName.equals(CAMP_MODULE_CALL)) {
						if (checkModuleCallIsValid(n, t)) {
							moduleCalls.add(n);
						}
					} else if (fnName.equals(CAMP_USING_CALL)) {
						usingCalls.add(n);
					}
				}
			}
		}


		private void processMainAndExports(Node n, NodeTraversal t, List<Node> exportsList) {
			if (n.getType() == Token.GETPROP || n.getType() == Token.GETELEM) {
				Node firstChild = n.getFirstChild();
				if (firstChild.getType() == Token.NAME) {
					String propName = firstChild.getString();
					if (propName.equals(EXPORTS)) {
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

			if (!campModuleTransformInfoMap.containsKey(key)) {
				initCampModuleTransformInfo(key, parent);
			}

			CampModuleTransformInfo campModuleTransformInfo = campModuleTransformInfoMap.get(key);
			
			List<Node> usingCalls = campModuleTransformInfo.getUsingCallList();
			List<Node> moduleCalls = campModuleTransformInfo.getModuleCallList();
			List<Node> exportsList = campModuleTransformInfo.getExportsList();
			List<JSDocInfo> jsdocList = campModuleTransformInfo.getJsDocList();
			
			JSDocInfo info = n.getJSDocInfo();
			
			if (n.getType() == Token.CALL) {
				processModuleAndUsing(n, t, moduleCalls, usingCalls);
			} else {
				processMainAndExports(n, t, exportsList);
			}
			if (info != null) {
				jsdocList.add(info);
			}
		}
	}


	private final class Processor {

		private CampModuleTransformInfo campModuleTransformInfo;

		private Node scriptBody;

		private String moduleName;

		private String[] modulePathComponentList;

		private List<Node> exportsList;

		private List<JSDocInfo> jsdocList;		
		
		public Processor(CampModuleTransformInfo campModuleTransformInfo, Node scriptBody, String moduleName, String[] modulePathComponentList) {
			this.campModuleTransformInfo = campModuleTransformInfo;
			this.scriptBody = scriptBody;
			this.moduleName = moduleName;
			this.modulePathComponentList = modulePathComponentList;
			this.exportsList = this.campModuleTransformInfo.getExportsList();
			this.jsdocList = this.campModuleTransformInfo.getJsDocList();
		}

		public void processModules() {
			this.processCampUsing();
			this.processModuleExports();
			this.processExportedClassName();
		}

		
		private void processCampUsing () {
			List<Node> usingCallList = this.campModuleTransformInfo.getUsingCallList();
			int index = 0;
			Node firstChild = null;
			
			for (Node campUsingCall : usingCallList) {

				Node parent = campUsingCall.getParent();
				Node firstArg = campUsingCall.getFirstChild().getNext();
				
				if (firstArg.getType() == Token.STRING) {

					Node expResult = this.createGoogRequireCallFrom(firstArg.getString(), campUsingCall);
					String[] moduleQualifiedName = firstArg.getString().split("\\.", 0);
					
					if (moduleQualifiedName.length > 0) {
						Node module = createModuleQualifiedName(moduleQualifiedName);
						if (parent.getType() == Token.EXPR_RESULT) {
							parent.copyInformationFromForTree(module);
							parent.getParent().replaceChild(parent, module);
						} else {
							campUsingCall.copyInformationFromForTree(module);
							campUsingCall.getParent().replaceChild(campUsingCall, module);
						}
					} else {
						if (parent.getType() == Token.EXPR_RESULT) {
							parent.getParent().removeChild(parent);
						} else {
							campUsingCall.detachFromParent();
						}
					}
					if (index == 0) {
						this.scriptBody.addChildToFront(expResult);
						index++;
					} else {
						this.scriptBody.addChildAfter(expResult, firstChild);
						index++;
					}
					firstChild = expResult;
				}
			}
			compiler.reportCodeChange();
		}


		/**
		 * <code>goog.require</code> を
		 * <code>camp.using</code> から生成する。
		 * @param namespace
		 * @param informationHolder
		 * @return <code>goog.require</code> のノード
		 */
		private Node createGoogRequireCallFrom(String namespace, Node informationHolder) {
			Node ns = Node.newString(namespace);
			Node name = Node.newString(Token.NAME, "goog");
			Node require = new Node(Token.GETPROP, name, Node.newString("require"));
			Node call = new Node(Token.CALL, require, ns);
			Node expResult = new Node(Token.EXPR_RESULT, call);
			informationHolder.copyInformationFromForTree(call);
			return expResult;
		}
		
		
		/**
		 * <code>exports.~</code> 形式のコードを変換する
		 */
		private void processModuleExports() {
			List<Node> campModuleCallList = this.campModuleTransformInfo.getModuleCallList();
			
			for (Node campModuleCall : campModuleCallList) {
				
				Node parent = campModuleCall.getParent();
				Node campModuleNameNode = campModuleCall.getFirstChild().getNext();
				this.createGoogProvideCallFrom(campModuleNameNode.getString());
				this.cleanCampModule(campModuleCall, campModuleNameNode);				
				compiler.reportCodeChange();
			}
		}

		
		/**
		 * <code>exports.~</code> から
		 * <code>goog.provide</code> 形式に変換する
		 * @param campModulePath camp.moduleのモジュール名
		 * @param informationHolder camp.moduleノード
		 */
		private void createGoogProvideCallFrom(String campModulePath) {
			
			List<Node> exportsList = this.campModuleTransformInfo.getExportsList();
			HashMap<String, Boolean> cache = new HashMap<String, Boolean>();
			
			for (Node exported : exportsList) {
				String key = exported.getFirstChild().getNext().getString();
				if (key.equals("main")) {
					if (exported.getNext().getType() == Token.FUNCTION) {
						this.transformMain(exported);
					}
				} else if (!cache.containsKey(key)) {
					cache.put(key, true);
					this.createGoogProvideFromExported(campModulePath, key, exported);
				}
			}
		}


		/**
		 * main関数を即時実行関数に変換する
		 * @param exported main関数のノード
		 */
		private void transformMain(Node exported) {
			Node function = exported.getNext();
			function.detachFromParent();
			Node call = new Node(Token.CALL, function);
			exported.getParent().getParent().replaceChild(exported.getParent(), call);
		}


		/**
		 * <code>exports.~</code> として宣言されているクラスに対応する
		 * <code>goog.provide</code> を生成する。
		 * @param modulePath 現在のmoduleのパス
		 * @param informationHolder 元のexports.の部分
		 */
		private void createGoogProvideFromExported(String modulePath, String className, Node informationHolder) {
			Node ns = Node.newString(modulePath + "." + className);
			Node name = Node.newString(Token.NAME, "goog");
			Node require = new Node(Token.GETPROP, name, Node.newString("provide"));
			Node call = new Node(Token.CALL, require, ns);
			Node expResult = new Node(Token.EXPR_RESULT, call);
			informationHolder.copyInformationFromForTree(call);
			this.scriptBody.addChildToFront(expResult);
		}


		/**
		 * <code>camp.module</code> 形式のコードを
		 * <code>goog.scope</code> 形式に変換し、
		 * ゴミを削除する
		 * @param campModule
		 * @param campModuleNameNode
		 */
		private void cleanCampModule(Node campModule, Node campModuleNameNode) {
			Node nameNodes = campModule.getFirstChild().getFirstChild();
			nameNodes.setString("goog");
			nameNodes.getNext().setString("scope");
			campModuleNameNode.getNext().getFirstChild().getNext().removeChildren();
			campModuleNameNode.detachFromParent();
		}

		
		/**
		 * <code>exports.~</code> 形式のコードとjsdocを
		 * 完全修飾名に変換する。
		 * @param nil
		 * @return
		 */
		private void processExportedClassName () {
			
			List<Node> exportsList = this.campModuleTransformInfo.getExportsList();
			List<JSDocInfo> jsdocList = this.campModuleTransformInfo.getJsDocList();
			Node module = createModuleQualifiedName(this.modulePathComponentList);
			
			for (Node exports : exportsList) {
				this.convertExportsToFqn(exports, module);
			}
			
			for (JSDocInfo jsdoc : jsdocList) {
				for (Node type : jsdoc.getTypeNodes()) {
					fixTypeNode(type, this.moduleName);
				}
			}
			
			compiler.reportCodeChange();
		}

		
		/**
		 * <code>exports.~</code> 形式のコードを完全修飾名に変換する
		 * @param exports
		 * @param module
		 */
		private void convertExportsToFqn(Node exports, Node module) {
			Node exportsToken = exports.getFirstChild();
			Node nameToken = exportsToken.getNext();
			Node cloned = module.cloneTree();
			Node child = new Node(exports.getType(), cloned, Node.newString(nameToken.getString()));
			nameToken.detachFromParent();
			child.useSourceInfoFromForTree(exports);
			child.setJSDocInfo(exports.getJSDocInfo());
			exports.getParent().replaceChild(exports, child);
		}

		
		/**
		 * 型名に <code>exports.~</code> 形式の表記があれば
		 * 完全修飾名に変換する
		 * @param typeNode
		 * @param moduleName
		 */
		private void fixTypeNode(Node typeNode, String moduleName) {
			if (typeNode.isString()) {
				String name = typeNode.getString();
				if (name.indexOf(EXPORTS + '.') != -1) {
					typeNode.setString(name.replaceAll(EXPORTS + '.', moduleName + '.'));
				}
			}

			for (Node child = typeNode.getFirstChild(); child != null;
				 child = child.getNext()) {
				fixTypeNode(child, moduleName);
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
		
		for (String key : campModuleTransformInfoMap.keySet()) {
			
			CampModuleTransformInfo campModuleTransformInfo = campModuleTransformInfoMap.get(key);
			List<Node> moduleCalls = campModuleTransformInfo.getModuleCallList();
			
			if (moduleCalls.size() > 0) {
				Node scriptBody = rootHolder.get(key);
				String moduleName = moduleCalls.get(0).getFirstChild().getNext().getString();
				String[] modulePathComponentList = moduleName.split("\\.", 0);
				Processor processor = new Processor(campModuleTransformInfo, scriptBody, moduleName, modulePathComponentList);
				processor.processModules();
			}
		}
	}
}
