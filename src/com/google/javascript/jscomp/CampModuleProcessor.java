package com.google.javascript.jscomp;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.List;

import com.google.javascript.jscomp.AbstractCompiler;
import com.google.javascript.jscomp.CompilerPass;
import com.google.javascript.jscomp.DiagnosticType;
import com.google.javascript.jscomp.NodeTraversal.AbstractPostOrderCallback;
import com.google.javascript.jscomp.NodeTraversal.AbstractScopedCallback;
import com.google.javascript.jscomp.NodeTraversal;
import com.google.javascript.jscomp.NodeUtil;
import com.google.javascript.jscomp.Scope;
import com.google.javascript.jscomp.Scope.Var;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.Token;
import com.google.javascript.rhino.JSDocInfo;
import com.google.javascript.jscomp.CampModuleTransformInfo;
import com.google.common.base.Strings;

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
		
		
		/**
		 * rootノードを取得する
		 * @param n
		 * @return rootノード
		 */
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

		
		/**
		 * <code>CampModuleTransformInfo</code> の初期化
		 * @param key
		 * @param parent
		 * @return
		 */
		private void initCampModuleTransformInfo(String key, Node parent) {
			isMainAlreadyFounded = false;
			firstDefined = "";
			rootHolder.put(key, getRoot(parent));
			campModuleTransformInfoMap.put(key, new CampModuleTransformInfo());
		}

		
		/**
		 * <code>camp.module</code> の呼び出しが正しく行われているか確認する。
		 * @param n
		 * @param t
		 * @return 正否
		 */
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

		
		/**
		 * <code>camp.module</code> と
		 * <code>camp.using</code> の呼び出しをリストにまとめる。
		 * @param n
		 * @param t
		 * @param moduleCalls
		 * @param usingCalls
		 */
		private void collectModuleAndUsing(Node n, NodeTraversal t, List<Node> moduleCalls, List<Node> usingCalls, Map<String, Node> aliasMap) {
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
						Node maybeNameNode = n.getParent();
						//var Baz = camp.using('foo.bar.Baz');
						//変数に束縛されているusing宣言を記録
						if (maybeNameNode.isName()) {
							Node maybeVar = maybeNameNode.getParent();
							if (maybeVar.isVar()) {
								aliasMap.put(maybeNameNode.getString(), n);
							}
						}
					}
				}
			}
		}


		/**
		 * <code>exports.~</code> のノードをリストにまとめる。
		 * @param n
		 * @param t
		 * @param exportsList
		 * @return
		 */
		private void collectMainAndExports(Node n, NodeTraversal t, List<Node> exportsList) {
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
			Map<String, Node> aliasMap = campModuleTransformInfo.getAliasMap();

			if (n.getType() == Token.CALL) {
				collectModuleAndUsing(n, t, moduleCalls, usingCalls, aliasMap);
			} else {
				collectMainAndExports(n, t, exportsList);
			}
		}
	}

	
	private class AliasResolver extends AbstractScopedCallback {
		
		private int scopeDepth = 0;		
		
		@Override
		public void visit(NodeTraversal t, Node n, Node parent) {
			
			CampModuleTransformInfo campModuleTransformInfo = campModuleTransformInfoMap.get(t.getSourceName());
			Map<String, Node> aliasMap = campModuleTransformInfo.getAliasMap();
			List<CampModuleTransformInfo.JSDocAndScopeInfo> jsdocInfoList = campModuleTransformInfo.getJsDocInfoList();
			Scope scope = t.getScope();
			
			
			if (isRewritableNode(n)) {
				String name = n.getString();
				if (isAlias(name, scope, campModuleTransformInfo, this.scopeDepth)) {
					Node getprop = createModuleQualifiedName(aliasMap.get(name).getFirstChild().getNext().getString().split("\\."));
					getprop.copyInformationFromForTree(n);
					n.getParent().replaceChild(n, getprop);
				} else {
					renameVar(n, name, scope, campModuleTransformInfo.getRenameMap(), scopeDepth, campModuleTransformInfo.getModuleId());
				}
			}
			
			JSDocInfo jsDocInfo = n.getJSDocInfo();
			if (jsDocInfo != null) {
				CampModuleTransformInfo.JSDocAndScopeInfo jsDocAndScopeInfo = campModuleTransformInfo.new JSDocAndScopeInfo(jsDocInfo, scope, this.scopeDepth, campModuleTransformInfo.getModuleId());
				jsdocInfoList.add(jsDocAndScopeInfo);
			}
		}
		
		private boolean isRewritableNode(Node n) {
			Node parentNode = n.getParent();
			if (n.isName() && !parentNode.isVar() && !parentNode.isParamList()) {
				if (Strings.isNullOrEmpty(n.getString())) {
					return false;
				}
				if (parentNode.isGetElem() || parentNode.isGetProp()) {
					if (!parentNode.getFirstChild().equals(n)) {
						return false;
					}
					if (parentNode.getFirstChild().isGetElem() || parentNode.getFirstChild().isGetProp()) {
						return false;
					}
				}
				return true;
			}
			return false;
		}
		
		@Override
		public void enterScope(NodeTraversal t) {
			this.scopeDepth++;
		}
		
		@Override
		public void exitScope(NodeTraversal t) {
			this.scopeDepth--;
			if (this.scopeDepth == 1) {
				CampModuleTransformInfo campModuleTransformInfo = campModuleTransformInfoMap.get(t.getSourceName());
				Scope scope = t.getScope();
				Iterator<Var> iter = scope.getVars();
				while (iter.hasNext()) {
					Var var = iter.next();
					Node nameNode = var.getNameNode();
					
					if (!nameNode.getParent().isParamList()) {
						String name = nameNode.getString();
						Map<String, String> varRenameMap = campModuleTransformInfo.getRenameMap();
						String newName = varRenameMap.get(name);
						
						if (newName == null) {
							newName = name + campModuleTransformInfo.getModuleId();
							varRenameMap.put(name, newName);
						} else if (name.equals(newName)) {
							continue;
						}
						
						if (!name.equals(newName)) {
							nameNode.setString(newName);
						}
					}
				}
			}
		}
		
	}
	
	
	private final class Processor {

		private CampModuleTransformInfo campModuleTransformInfo;

		private Node scriptBody;

		private String moduleName;

		private String[] modulePathComponentList;

		
		public Processor(CampModuleTransformInfo campModuleTransformInfo, Node scriptBody, String moduleName, String[] modulePathComponentList) {
			this.campModuleTransformInfo = campModuleTransformInfo;
			this.scriptBody = scriptBody;
			this.moduleName = moduleName;
			this.modulePathComponentList = modulePathComponentList;
		}

		public void processModules() {
			this.processCampUsing();
			this.processModuleExports();
			this.processExportedClassName();
		}

		
		/**
		 * <code>camp.using</code>の呼び出しを
		 * <code>goog.require</code>に変換する
		 * @param nil
		 * @return
		 */
		private void processCampUsing () {
			List<Node> usingCallList = this.campModuleTransformInfo.getUsingCallList();
			
			int index = 0;
			Node firstChild = null;
			
			for (Node campUsingCall : usingCallList) {

				Node parent = campUsingCall.getParent();
				Node firstArg = campUsingCall.getFirstChild().getNext();
				
				if (firstArg.getType() == Token.STRING) {

					Node expResult = this.createGoogRequireCallFrom(firstArg.getString(), campUsingCall);
					
					if (parent.isName() && parent.getParent().isVar()) {
						parent.getParent().detachFromParent();
					} else {
						parent.detachFromParent();
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
			Node expressionWithScopeCall = campModule.getParent();
	        Node scopeClosureBlock = campModule.getLastChild().getLastChild();
	        scopeClosureBlock.detachFromParent();
	        expressionWithScopeCall.getParent().replaceChild(
	            expressionWithScopeCall,
	            scopeClosureBlock);
	        NodeUtil.tryMergeBlock(scopeClosureBlock);
		}

		
		/**
		 * <code>exports.~</code> 形式のコードとjsdocを
		 * 完全修飾名に変換する。
		 * @param nil
		 * @return
		 */
		private void processExportedClassName () {
			
			List<Node> exportsList = this.campModuleTransformInfo.getExportsList();
			List<CampModuleTransformInfo.JSDocAndScopeInfo> jsdocInfoList = this.campModuleTransformInfo.getJsDocInfoList();
			Node module = createModuleQualifiedName(this.modulePathComponentList);
			
			for (Node exports : exportsList) {
				this.convertExportsToFqn(exports, module);
			}
			
			for (CampModuleTransformInfo.JSDocAndScopeInfo jsDocAndScopeInfo : jsdocInfoList) {
				JSDocInfo jsDocInfo = jsDocAndScopeInfo.getJsDocInfo();
				for (Node type : jsDocInfo.getTypeNodes()) {
					fixTypeNode(type, this.moduleName, jsDocAndScopeInfo.getScope(), jsDocAndScopeInfo.getDepth());
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
		private void fixTypeNode(Node typeNode, String moduleName, Scope scope, int depth) {
			Map<String, Node> aliasMap = this.campModuleTransformInfo.getAliasMap();
			if (typeNode.isString()) {
				String name = typeNode.getString();
				if (name.indexOf(EXPORTS + '.') != -1) {
					typeNode.setString(name.replaceAll(EXPORTS + '.', moduleName + '.'));
				} else if (isAlias(name, scope, this.campModuleTransformInfo, depth)) {
					typeNode.setString(aliasMap.get(name).getFirstChild().getNext().getString());
				} else {
					renameVar(typeNode, name, scope, this.campModuleTransformInfo.getRenameMap(),
							depth, this.campModuleTransformInfo.getModuleId());
				}
			}

			for (Node child = typeNode.getFirstChild(); child != null;
				 child = child.getNext()) {
				fixTypeNode(child, moduleName, scope, depth);
			}
		}
	}


	public CampModuleProcessor(AbstractCompiler compiler) {
		this.compiler = compiler;
	}

	
	/**
	 * 完全修飾名を生成する。
	 * @param moduleNames モジュール名をドットで区切った配列
	 * @return GETPROPノード
	 */
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

	
	private boolean isAlias(String name, Scope scope, CampModuleTransformInfo campModuleTransformInfo, int scopeDepth) {
		Map<String, Node> aliasMap = campModuleTransformInfo.getAliasMap();
		if (name.equals(EXPORTS)) {
			return false;
		}
		if (scopeDepth > 1 && aliasMap.containsKey(name)) {
			Var var = getVarFromScope(name, scope);
			if (var != null && scopeDepth == 2) {
				Node initialValue = var.getInitialValue();
				if (initialValue != null) {
					return aliasMap.get(name).equals(initialValue);
				}
			} else {
				return (var != null)? false : isAlias(name, scope.getParent(), campModuleTransformInfo, scopeDepth - 1);
			}
		}
		return false;
	}
	
	
	private void renameVar(Node target, String name, Scope scope, Map<String, String> varRenameMap, int depth, int moduleId) {
		if (scope != null) {
			Var var = getVarFromScope(name, scope);
			if (var != null && depth == 2) {
				String newName;
				if (varRenameMap.containsKey(name)) {
					newName = varRenameMap.get(name);
				} else {
					newName = name + moduleId;
					varRenameMap.put(name, newName);
				}
				target.setString(newName);
			} else if (var == null && depth > 2) {
				renameVar(target, name, scope.getParent(), varRenameMap, depth - 1, moduleId);
			}
		}
	}
	
	
	private Var getVarFromScope(String name, Scope scope) {
		Iterator<Var> iter = scope.getVars();
		while (iter.hasNext()) {
			Var var = iter.next();
			if (var.getName().equals(name)) {
				return var;
			}
		}
		return null;
	}
	
	
	@Override
	public void process(Node externs, Node root) {
		
		NodeTraversal.traverse(compiler, root, new ModuleResolver());
		NodeTraversal.traverse(compiler, root, new AliasResolver());
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
