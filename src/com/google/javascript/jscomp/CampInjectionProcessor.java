package com.google.javascript.jscomp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.tools.ant.util.StringUtils;

import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.javascript.jscomp.CompilerPass;
import com.google.javascript.jscomp.NodeTraversal.AbstractPostOrderCallback;
import com.google.javascript.jscomp.Scope.Var;
import com.google.javascript.rhino.JSDocInfo;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.Token;

class CampInjectionProcessor {
	
	static final DiagnosticType MESSAGE_CREATE_INSTANCE_TARGET_NOT_VALID =
		DiagnosticType.error("JSC_MSG_CREATE_INSTANCE_TARGET_NOT_VALID.", "The argument of camp.dependencies.injector.createInstance must be a constructor.");
	
	static final DiagnosticType MESSAGE_BIND_CALL_IS_NOT_VALID =
		DiagnosticType.error("JSC_MSG_BIND_CALL_IS_NOT_VALID.", "The first argument of camp.dependencies.injector.bind must be a string.");
	
	static final DiagnosticType MESSAGE_DEFINE_PROVIDER_FIRST_ARGUMENT_IS_NOT_VALID =
		DiagnosticType.error("JSC_MSG_DEFINE_PROVIDER_FIRST_ARGUMENT_IS_NOT_VALID.", "The first argument of camp.dependencies.injector.defineProvider must be a class.");
	
	static final DiagnosticType MESSAGE_DEFINE_PROVIDER_SECOND_ARGUMENT_IS_NOT_VALID =
		DiagnosticType.error("JSC_MSG_DEFINE_PROVIDER__SECOND_ARGUMENT_IS_NOT_VALID.", "The second argument of camp.dependencies.injector.defineProvider must be a function.");
	
	private final String CREATE_INSTANCE_CALL = "camp.dependencies.injector.createInstance";
	
	private final String BIND_CALL = "camp.dependencies.injector.bind";
	
	private final String DEFINE_PROVIDER_CALL = "camp.dependencies.injector.defineProvider";
	
	private final String INJECT_CALL = "camp.dependencies.injector.inject";
	
	private final String SINGLETON_CALL = "goog.addSingletonGetter";
	
	private final String PROTOTYPE = "prototype";
	
	private final String PROTOTYPE_REGEX = "(.*\\.prototype\\..*|.*\\.prototype$)";
	
	private Map<String, ClassInfo> classInfoMap = new HashMap<String, ClassInfo>();
	
	private InjectionTargetInfo injectionTargetInfo = new InjectionTargetInfo();
	
	private final class Pair<T,S> {
		private T first;
		private S second;
		
		public Pair(T first, S second) {
			this.first = first;
			this.second = second;
		}
		
		public T getFirst() {
			return this.first;
		}
		
		public S getSecond() {
			return this.second;
		}
	}
	
	private final class InjectionTargetInfo {
		private Map<String, Node> createInstanceTargetMap = new HashMap<String, Node>();
		
		private Map<String, Node> providerMap = new HashMap<String, Node>();
		
		private Map<String, List<String>> setterMap = new HashMap<String, List<String>>();
		
		private Map<String, Map<String, PrototypeInfo>> prototypeInfoMap = new HashMap<String, Map<String, PrototypeInfo>>();
		
		private Map<String, Integer> singletonMap = new HashMap<String, Integer>();
		
		private Map<String, Pair<String, Node>> bindTargetMap = new HashMap<String, Pair<String, Node>>();
		
		public void putCreateInstanceTarget(String name, Node n) {
			this.createInstanceTargetMap.put(name, n);
		}
		
		public Node getCreateInstanceTarget(String name) {
			return this.createInstanceTargetMap.get(name);
		}
		
		public Set<String> getCreateInstanceTargetMapIter() {
			return this.createInstanceTargetMap.keySet();
		}
		
		public boolean hasCreateInstanceTarget(String name) {
			return this.createInstanceTargetMap.containsKey(name);
		}
		
		public void putProvider(String name, Node n) {
			this.providerMap.put(name, n);
		}
		
		public Node getProvider(String name) {
			return this.providerMap.get(name);
		}
		
		public boolean hasProvider(String name) {
			return this.providerMap.containsKey(name);
		}
		
		public void putSetter(String className, String setterName) {
			if (!this.setterMap.containsKey(className)) {
				this.setterMap.put(className, new ArrayList<String>());
			}
			List<String> setterList = this.setterMap.get(className);
			setterList.add(setterName);
		} 
		
		public List<String> getSetterMap(String name) {
			return this.setterMap.get(name);
		}
		
		public boolean hasSetter(String name) {
			return this.setterMap.containsKey(name);
		}
		
		public void putSingleton(String className) {
			this.singletonMap.put(className, 1);
		}
		
		public boolean isSingleton(String className) {
			return this.singletonMap.containsKey(className);
		}
		
		public void putPrototypeInfo(String className, PrototypeInfo prototypeInfo) {
			Map<String, PrototypeInfo> prototypeInfoList;
			if (this.prototypeInfoMap.containsKey(className)) {
				prototypeInfoList = prototypeInfoMap.get(className);
			} else {
				prototypeInfoList = Maps.newHashMap();
				this.prototypeInfoMap.put(className, prototypeInfoList);
			}
			prototypeInfoList.put(prototypeInfo.getMethodName(), prototypeInfo);
		}
		
		public boolean hasPrototypeInfo(String className) {
			return this.prototypeInfoMap.containsKey(className);
		}
		
		public Map<String, PrototypeInfo> getPrototypeInfo(String className) {
			return this.prototypeInfoMap.get(className);
		}
		
		public void putBindTarget(String bindingName, Node node, String name) {
			this.bindTargetMap.put(bindingName, new Pair<String,Node>(name, node));
		}
		
		public Pair<String, Node> getBindTarget(String name) {
			return this.bindTargetMap.get(name);
		}
		
		public boolean hasBindTarget(String name) {
			return this.bindTargetMap.containsKey(name);
		}
		
		public Set<String> getBindTargetIter() {
			return this.bindTargetMap.keySet();
		}
	}
	
	private final class PrototypeInfo {
		private List<String> paramList = Lists.newArrayList();
		private String name;
		
		public PrototypeInfo(String name) {
			this.name = name;
		}
		
		public void addParam(String name) {
			this.paramList.add(name);
		}
		
		public List<String> getParamList() {
			return this.paramList;
		}
		
		public String getMethodName() {
			return this.name;
		}
	}
	
	private final class ClassInfo {
		private String className;
		
		private List<String> paramList = Lists.newArrayList();
		
		private List<String> setterList;
		
		private Node provider;
		
		private boolean isSingleton;
		
		private Map<String, PrototypeInfo> prototypeInfoMap;

		public ClassInfo(String className) {
			this.className = className;
		}

		public String getClassName() {
			return this.className;
		}
		
		public void addParam(String name) {
			paramList.add(name);
		}
		
		public List<String> getParamList() {
			return this.paramList;
		} 
		
		/**
		 * @return the isSingleton
		 */
		public boolean isSingleton() {
			return isSingleton;
		}

		/**
		 * @param isSingleton the isSingleton to set
		 */
		public void setSingleton(boolean isSingleton) {
			this.isSingleton = isSingleton;
		}

		/**
		 * @return the setterList
		 */
		public List<String> getSetterList() {
			return setterList;
		}

		/**
		 * @param setterList the setterList to set
		 */
		public void setSetterList(List<String> setterList) {
			if (this.setterList == null) {
				this.setterList = setterList;
			} else {
				this.setterList.addAll(setterList);
			}
		}

		/**
		 * @return the provider
		 */
		public Node getProvider() {
			return provider;
		}

		/**
		 * @param provider the provider to set
		 */
		public void setProvider(Node provider) {
			this.provider = provider;
		}

		/**
		 * @return the prototypeInfoMap
		 */
		public PrototypeInfo getPrototypeInfo(String name) {
			return prototypeInfoMap.get(name);
		}

		/**
		 * @param prototypeInfoMap the prototypeInfoMap to set
		 */
		public void setPrototypeInfoMap(Map<String, PrototypeInfo> prototypeInfoMap) {
			if (this.prototypeInfoMap == null) {
				this.prototypeInfoMap = prototypeInfoMap;
			} else {
				this.prototypeInfoMap.putAll(prototypeInfoMap);
			}
		}
	}
	
	private final class InjectionFinder extends AbstractPostOrderCallback {
		
		@Override
		public void visit(NodeTraversal t, Node n, Node parent) {
			if (n.isCall()) {
				Node maybeGetProp = n.getFirstChild();
				if (maybeGetProp.isGetProp()) {
					String qualifiedName = maybeGetProp.getQualifiedName();
					if (qualifiedName != null) {
						if (qualifiedName.equals(CREATE_INSTANCE_CALL)) {
							maybeGetProp = maybeGetProp.getNext();
							if (maybeGetProp.isGetProp()) {
								injectionTargetInfo.putCreateInstanceTarget(maybeGetProp.getQualifiedName(), n);
							} else if (maybeGetProp.isName()) {
								injectionTargetInfo.putCreateInstanceTarget(maybeGetProp.getString(), n);
							}
						} else if (qualifiedName.equals(BIND_CALL)) {
							String bindingName = n.getFirstChild().getNext().isString()? n.getFirstChild().getNext().getString() : null;
							if (bindingName == null) {
								t.report(n, MESSAGE_BIND_CALL_IS_NOT_VALID);
							}
							maybeGetProp = maybeGetProp.getNext();
							if (maybeGetProp.isGetProp()) {
								injectionTargetInfo.putBindTarget(bindingName, maybeGetProp, maybeGetProp.getQualifiedName());
							} else if (maybeGetProp.isName()) {
								injectionTargetInfo.putBindTarget(bindingName, maybeGetProp, maybeGetProp.getString());
							}
						} else if (qualifiedName.equals(INJECT_CALL)) {
							Node targetClass = n.getFirstChild().getNext();
							if (targetClass == null) {
								t.report(n, MESSAGE_DEFINE_PROVIDER_FIRST_ARGUMENT_IS_NOT_VALID);
							}
							Node injections = targetClass.getNext();
							
							if (injections == null) {
								t.report(n, MESSAGE_DEFINE_PROVIDER_SECOND_ARGUMENT_IS_NOT_VALID);
							}
							
							qualifiedName = targetClass.getQualifiedName();
							if (qualifiedName != null) {
								while (injections != null) {
									if (injections.isString()) {
										injectionTargetInfo.putSetter(qualifiedName, injections.getString());
									}
									injections = injections.getNext();
								}
							}
							n.getParent().detachFromParent();
						} else if (qualifiedName.equals(DEFINE_PROVIDER_CALL)) {
							Node targetClass = n.getFirstChild().getNext();
							if (targetClass == null) {
								t.report(n, MESSAGE_DEFINE_PROVIDER_FIRST_ARGUMENT_IS_NOT_VALID);
							}
							Node provider = targetClass.getNext();
							
							if (provider == null) {
								t.report(n, MESSAGE_DEFINE_PROVIDER_SECOND_ARGUMENT_IS_NOT_VALID);
							}
							
							qualifiedName = targetClass.getQualifiedName();
							if (qualifiedName != null) {
								injectionTargetInfo.putProvider(qualifiedName, provider);
							}
							n.getParent().detachFromParent();
						} else if (qualifiedName.equals(SINGLETON_CALL)) {
							Node targetClass = n.getFirstChild().getNext();
							if (targetClass != null) {
								
							}
						}
					}
				}
			} else if (n.isAssign()) {
				Node lvalue = n.getFirstChild();
				Node rvalue = lvalue.getNext();
				if ((lvalue.isGetProp() || lvalue.isGetElem())) {
					this.collectPrototype(t, lvalue, rvalue);
				}
			}
		}
		
		private void collectPrototype(NodeTraversal t, Node property, Node rvalue) {
			String qualifiedName = property.getQualifiedName();
			if (qualifiedName != null) {
				String[] nameArr = qualifiedName.split("\\.");
				if (nameArr.length > 1 && qualifiedName.indexOf("." + PROTOTYPE) > -1) {
					String className = qualifiedName.substring(0, qualifiedName.indexOf("." + PROTOTYPE));
					
					//foo.prototype.bar = function() {...
					if (rvalue.isFunction() && qualifiedName.matches(PROTOTYPE_REGEX)) {
						String methodName = nameArr[nameArr.length - 1];
						System.out.println(className);
						this.addPrototypeMember(className, methodName, rvalue);
						
					} else if (qualifiedName.endsWith("." + PROTOTYPE) && rvalue.isObjectLit()) {
					//foo.prototype = {...
						Node child = rvalue.getFirstChild();
						Node function;
						String propertyName;
						for (;child != null; child = child.getNext()) {
							if (child.isStringKey()) {
								propertyName = child.getString();
								function = child.getFirstChild();
								if (function.isFunction()) {
									addPrototypeMember(className, propertyName, function);
								}
							}
						}
					}
				}
			}
		}
		
		private void addPrototypeMember(String className, String methodName, Node function) {
			PrototypeInfo prototypeInfo = new PrototypeInfo(methodName);
			Node paramList = function.getFirstChild().getNext();
			for (Node param : paramList.children()) {
				prototypeInfo.addParam(param.getString());
			}
			injectionTargetInfo.putPrototypeInfo(className, prototypeInfo);
		}
	}
	
	private final class ClassFinder extends AbstractPostOrderCallback {
		private Map<String, Map<String, Node>> lvalueMap = new HashMap<String, Map<String, Node>>(); 
		
		private Map<String, Node> scopedLvalueMap;
		
		@Override
		public void visit(NodeTraversal t, Node n, Node parent) {
			if (!lvalueMap.containsKey(t.getSourceName())) {
				scopedLvalueMap = new HashMap<String, Node>();
				lvalueMap.put(t.getSourceName(), scopedLvalueMap);
			}
			switch (n.getType()) {
			case Token.ASSIGN :
				this.checkAssignment(t, n);
			break;
			
			case Token.VAR :
				this.checkVar(t, n);
			break;
			
			case Token.FUNCTION :
				this.checkFunction(t, n);
			break;
			}
		}
		
		private void checkAssignment(NodeTraversal t, Node n) {
			Node child = n.getFirstChild();
			if (child.isGetProp()) {
				
				String qualifiedName = child.getQualifiedName();
				
				if (qualifiedName != null) {
					scopedLvalueMap.put(qualifiedName, child.getNext());
					if (injectionTargetInfo.hasCreateInstanceTarget(qualifiedName)) {
						this.findDefinition(t, child.getNext(), qualifiedName, true);
					} else if (injectionTargetInfo.hasProvider(qualifiedName)) {
						this.findDefinition(t, child.getNext(), qualifiedName, true);
						ClassInfo info = classInfoMap.get(qualifiedName);
						if (info != null) {
							info.setProvider(injectionTargetInfo.getProvider(qualifiedName));
						}
					} else if (injectionTargetInfo.hasBindTarget(qualifiedName)) {
						this.findDefinition(t, child.getNext(), qualifiedName, false);
					}
				}
			}
		}
		
		private void checkVar(NodeTraversal t, Node n) {
			Node nameNode = n.getFirstChild();
			Node initialValue = nameNode.getFirstChild();
			if (initialValue != null) {
				String name = nameNode.getString();
				if (injectionTargetInfo.hasCreateInstanceTarget(name)) {
					this.findDefinition(t, initialValue, name, true);
				} else if (injectionTargetInfo.hasBindTarget(name)) {
					this.findDefinition(t, initialValue, name, false);
				}
			}
		}
		
		private void checkFunction(NodeTraversal t, Node n) {
			boolean isDeclaration = n.getParent().isExprResult();
			
			if (isDeclaration) {
				String name = n.getFirstChild().getString();
				if (!Strings.isNullOrEmpty(name)) {
					if (injectionTargetInfo.hasCreateInstanceTarget(name)) {
						this.findDefinition(t, n, name, true);
					} else if (injectionTargetInfo.hasBindTarget(name)) {
						this.findDefinition(t, n, name, false);
					}
				}
			}
		}
		
		private void findDefinition(NodeTraversal t, Node n, String name, boolean isCreateInstance) {
			this.doFindDefinition(t, n, name, isCreateInstance, new ClassInfo(name));
		}
		
		private void doFindDefinition(NodeTraversal t, Node n, String name, boolean isCreateInstance, ClassInfo classInfo) {
			switch (n.getType()) {
			case Token.NAME :
				String str = n.getString();
				if (str != null) {
					Var var = t.getScope().getVar(str);
					if (var != null) {
						Node value = var.getInitialValue();
						if (value != null) {
							this.setInfoToClassInfo(str, classInfo);
							this.findDefinition(t, value, name, isCreateInstance);
						}
					}
				}
			break;

			case Token.GETELEM :
			case Token.GETPROP :
				String qualifiedName = n.getQualifiedName();
				if (qualifiedName != null) {
					if (scopedLvalueMap.containsKey(qualifiedName)) {
						this.setInfoToClassInfo(qualifiedName, classInfo);
						this.findDefinition(t, scopedLvalueMap.get(qualifiedName), name, isCreateInstance);
					}
				}
			break;
			
			case Token.FUNCTION :
				JSDocInfo jsDocInfo = this.getJSDocInfoFrom(n);
				
				if (jsDocInfo != null) {
					if (!jsDocInfo.isConstructor()) {
						if (isCreateInstance) {
							t.report(n, MESSAGE_CREATE_INSTANCE_TARGET_NOT_VALID);
						} else {
							return;
						}
					}
				} else {
					if (isCreateInstance) {
						t.report(n, MESSAGE_CREATE_INSTANCE_TARGET_NOT_VALID);
					} else {
						return;
					}
				}
			
				Node paramList = n.getFirstChild().getNext();
				for (Node child : paramList.children()) {
					classInfo.addParam(child.getString());
				}
				classInfoMap.put(name, classInfo);
			break;
			
			default :
				if (isCreateInstance) {
					t.report(n, MESSAGE_CREATE_INSTANCE_TARGET_NOT_VALID);
				}
			}
		}
		
		private void setInfoToClassInfo(String name, ClassInfo classInfo) {
			if (injectionTargetInfo.hasProvider(name) && classInfo.getProvider() == null) {
				classInfo.setProvider(injectionTargetInfo.getProvider(name));
			}
			
			if (injectionTargetInfo.isSingleton(name) && !classInfo.isSingleton()) {
				classInfo.setSingleton(true);
			}
			
			if (injectionTargetInfo.hasPrototypeInfo(name)) {
				classInfo.setPrototypeInfoMap(injectionTargetInfo.getPrototypeInfo(name));
			}
			
			if (injectionTargetInfo.hasSetter(name)) {
				classInfo.setSetterList(injectionTargetInfo.getSetterMap(name));
			}
		}
		
		private JSDocInfo getJSDocInfoFrom(Node n) {
			JSDocInfo info = n.getJSDocInfo();
			if (info == null) {
				return getJSDocInfoFrom(n.getParent());
			}
			return info;
		}
	}
	
	private final class InjectionRewriter {		
		public void process() {
			
		}
		
		private void inliningCreateInstanceCall() {
			for (String key : injectionTargetInfo.getCreateInstanceTargetMapIter()) {
				Node createInstanceCall = injectionTargetInfo.getCreateInstanceTarget(key);
				ClassInfo info = classInfoMap.get(key);
				if (info != null) {
					if (info.getProvider() != null) {
						createInstanceCall.getParent().replaceChild(createInstanceCall, this.makeNewCallFromProvider(info));
					} else {
						createInstanceCall.getParent().replaceChild(createInstanceCall, this.makeNewCall(info));
					}
				}
			}
		}
		
		private Node resolveBinding(String bindingName) {
			if (injectionTargetInfo.hasBindTarget(bindingName)) {
				Pair<String, Node> binding = injectionTargetInfo.getBindTarget(bindingName);
				String name = binding.getFirst();
				ClassInfo info = classInfoMap.get(name);
				if (info != null) {
					if (info.getProvider() != null) {
						return this.makeNewCallFromProvider(info);
					} else {
						return this.makeNewCall(info);
					}
				} else {
					Node call = binding.getSecond();
					call.getParent().replaceChild(call, new Node(Token.ASSIGN, createQualifiedNameNode("camp.dependencies.injectionRegistry." + bindingName), createQualifiedNameNode(name)));
					return createQualifiedNameNode("camp.dependencies.injectionRegistry." + bindingName);
				}
			}
			return new Node(Token.NULL);
		}
		
		private Node makeNewCallFromProvider(ClassInfo info) {
			Node function = info.getProvider();
			Node paramList = function.getFirstChild().getNext();
			Node ret = new Node(Token.CALL, function.cloneTree());
			for (Node param : paramList.children()) {
				ret.addChildToBack(this.resolveBinding(param.getString()));
			}
			return ret;
		}
		
		private Node makeNewCall(ClassInfo classInfo) {
			Node newCall;
			if (classInfo.isSingleton()) {
				newCall = this.makeSingleton(classInfo);
			} else {
				newCall = new Node(Token.NEW, createQualifiedNameNode(classInfo.getClassName()));
				for (String param : classInfo.getParamList()) {
					newCall.addChildToBack(this.resolveBinding(param));
				}
			}
			
			if (classInfo.getSetterList() != null) {
				return this.makeNewCallScope(newCall, classInfo);
			}
			return newCall;
		}
		
		private Node makeSingleton(ClassInfo classInfo) {
			return new Node(Token.CALL, createQualifiedNameNode("camp.dependencies.getSingletonInstance"), createQualifiedNameNode(classInfo.getClassName()));
		}
		
		private Node makeNewCallScope(Node newCall, ClassInfo classInfo) {
			Node instanceVar = Node.newString(Token.NAME, "instance");
			Node block = new Node(Token.BLOCK,
									new Node(Token.VAR,
											instanceVar,
											newCall));
			
			for (String setterName : classInfo.getSetterList()) {
				PrototypeInfo prototypeInfo = classInfo.getPrototypeInfo(setterName);
				if (prototypeInfo != null) {
					Node setterCall = new Node(Token.CALL, createQualifiedNameNode("instance." + setterName));
					for (String param : prototypeInfo.getParamList()) {
						setterCall.addChildToBack(this.resolveBinding(param));
					}
					block.addChildToBack(setterCall);
				}
			}
			
			block.addChildToBack(new Node(Token.RETURN, instanceVar.cloneNode()));
			
			return new Node(Token.CALL,
					new Node(Token.FUNCTION,
							Node.newString(Token.NAME, ""),
							new Node(Token.PARAM_LIST), block));
		}
	}
	
	private Node createQualifiedNameNode (String name) {
		String[] moduleNames = name.split("\\.");
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
	
	private AbstractCompiler compiler;
	
	public CampInjectionProcessor(AbstractCompiler compiler) {
		this.compiler = compiler;
	}
	
	public void process(Node externs, Node root) {
		NodeTraversal.traverse(compiler, root, new InjectionFinder());
		NodeTraversal.traverse(compiler, root, new ClassFinder());
		new InjectionRewriter().process();
	}
	
}
