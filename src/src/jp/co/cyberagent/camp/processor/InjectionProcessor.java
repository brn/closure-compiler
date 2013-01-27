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
import com.google.javascript.rhino.jstype.UnionType;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.Token;
import com.google.javascript.rhino.JSDocInfo;
import com.google.javascript.rhino.JSDocInfoBuilder;
import com.google.javascript.rhino.JSTypeExpression;

import java.util.List;
import java.util.Set;

public final class InjectionProcessor implements CompilerPass {

	static final DiagnosticType TYPE_MISMATCH_WARNING =
		DiagnosticType.warning("JSC_TYPE_MISMATCH", "actual parameter \"{0}\" of {1} does not match formal parameter.\n"
							   + "found   : {2}\n"
							   + "required: {3}");
	
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

	static final DiagnosticType MESSAGE_CLASS_NOT_FOUND =
		DiagnosticType.error("JSC_MSG_CLASS_NOT_FOUND", "The target class is not found. The first argument of <camp.dependencies.injector.createInstance> must be passed a constructor directly.");
	
	private final AbstractCompiler compiler;

	private static final String INJECTION_CALL = "camp.dependencies.injector.inject";

	private static final String GET_INJECTION_CALL = "camp.dependencies.injector.get";
	
	private static final String PROVIDER_CALL = "camp.dependencies.injector.defineProvider";

	private static final String CAMP_SINGLETON_CALL = "camp.singleton";

	private static final String GOOG_SINGLETON_CALL = "goog.addSingletonGetter";

	private static final String INSTANIATION_CALL = "camp.dependencies.injector.createInstance";

	private static final String BINDING_CALL = "camp.dependencies.injector.bind";

	private static final String PROTOTYPE = "prototype";

	private static final String SINGLETON_CALL = "getInstance";

	private static final String GET_INSTANCE_MIRROR = "$jscomp$getInstance$mirror";

	private static final String INSTANCE_MIRROR = "$jscomp$instance$mirror";

	private ClassInjectionInfoRegistry classInjectionInfoRegistry = new ClassInjectionInfoRegistry();

	private BindingRegistry bindingRegistry = new BindingRegistry();
	
	private class SetterInjectionProcessor extends AbstractPostOrderCallback {
		
		private void processInjectionCall(NodeTraversal t, Node n) {
			Node firstChild = n.getFirstChild();
			if (firstChild.getType() == Token.GETPROP) {
				String fnName = firstChild.getQualifiedName();
				if (fnName != null) {
					boolean isSingleton = fnName.equals(CAMP_SINGLETON_CALL) || fnName.equals(GOOG_SINGLETON_CALL);
					boolean processed = false;
					if (fnName.equals(INJECTION_CALL) || isSingleton) {
						registerInjections(t, n, firstChild.getNext(), isSingleton, false);
						processed = true;
					} else if (fnName.equals(PROVIDER_CALL)) {
						registerInjections(t, n, firstChild.getNext(), false, true);
						processed = true;
					}
					if (processed && !isSingleton) {
						detachInjectionCall(n);
						compiler.reportCodeChange();
					}
				}
			}
		}


		private void registerInjections(NodeTraversal t, Node n, Node nameNode, boolean isSingleton, boolean isProvider) {
			if (nameNode != null) {
				String className = nameNode.getQualifiedName();
				if (className != null && classInjectionInfoRegistry.hasInfo(className)) {
					ClassInjectionInfo classInjectionInfo = classInjectionInfoRegistry.getInfo(className);
					Node argumentsNode = nameNode.getNext();
					if (!isProvider) {
						if (!isSingleton) {
							List<String> argumentsList = Lists.newArrayList();
							for (;argumentsNode != null; argumentsNode = argumentsNode.getNext()) {
								if (argumentsNode.isString()) {
									argumentsList.add(argumentsNode.getString());
								} else {
									t.report(argumentsNode, MESSAGE_SETTER_INJECTION_TARGET_MUST_BE_A_STRING, "");
								}
							}
							classInjectionInfo.setInjectionTargets(argumentsList);
						} else {
							classInjectionInfo.setSingleton(true);
							createSingletonGetterMirror(classInjectionInfo, n);
						}
					} else {
						classInjectionInfo.setProvider(argumentsNode.cloneTree());
					}
				} else {
					t.report(n, MESSAGE_INJECT_ARGUMENT_MUST_BE_A_CONSTRUCTOR, "");
				}
			} else {
				t.report(n, MESSAGE_INJECT_ARGUMENT_MUST_BE_A_CONSTRUCTOR, "");
			}
		}


		private Node createNodesFromString(String name) {
			String[] methodNames = name.split("\\.", 0);
			Node prop = null;
			for (String methodName : methodNames) {
				if (prop == null) {
					prop = Node.newString(Token.NAME, methodName);
				} else {
					prop = new Node(Token.GETPROP, prop, Node.newString(methodName));
				}
			}
			return prop;
		}
		

		private void createSingletonGetterMirror(ClassInjectionInfo classInjectionInfo, Node n) {
			Node constructor = classInjectionInfo.getConstructorNode();
			Node className = createNodesFromString(classInjectionInfo.getClassName());
			Node getInstanceMirror = Node.newString(GET_INSTANCE_MIRROR);
			Node createInstance = createNodesFromString(INSTANIATION_CALL);
			Node instanceHolder = new Node(Token.GETPROP,
										   className.cloneTree(),
										   Node.newString(INSTANCE_MIRROR));
			Node expr = new Node(Token.EXPR_RESULT,
								 new Node(Token.ASSIGN,
										  new Node(Token.GETPROP,
												   className,
												   getInstanceMirror),
										  new Node(Token.FUNCTION,
												   Node.newString(Token.NAME, ""),
												   new Node(Token.PARAM_LIST),
												   new Node(Token.BLOCK,
															new Node(Token.IF,
																	 new Node(Token.NOT,
																			  instanceHolder.cloneTree()),
																	 new Node(Token.BLOCK,
																			  new Node(Token.EXPR_RESULT,
																					   new Node(Token.ASSIGN,
																								instanceHolder.cloneTree(),
																								new Node(Token.TRUE))),
																			  new Node(Token.RETURN,
																					   new Node(Token.CALL,
																								createInstance,
																								className.cloneTree())))),
															new Node(Token.RETURN,
																	 new Node(Token.CALL,
																			  new Node(Token.GETPROP,
																					   className.cloneTree(),
																					   Node.newString(SINGLETON_CALL))))))));
			Node tmp = n.getParent();
			while (tmp != null && !tmp.isExprResult()) {tmp = tmp.getParent();}
			if (tmp != null) {
				tmp.getParent().addChildAfter(expr, tmp);
			}
			Node assign = expr.getFirstChild();
			JSDocInfoBuilder builder = new JSDocInfoBuilder(false);
			builder.recordReturnType(new JSTypeExpression(new Node(Token.BANG, className.cloneTree()), ""));
			JSDocInfo info = builder.build(assign);
			assign.setJSDocInfo(info);
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
							bindingRegistry.setPrimitiveBindings(name, bindingValue);
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
		private HashMap<String, Node> primitiveBindings = new HashMap<String, Node>();
		private HashMap<String, Node> classBindings = new HashMap<String, Node>();

		public void setPrimitiveBindings(String name, Node bindingValue) {
			primitiveBindings.put(name, bindingValue);
		}

		public void setClassBinding(String name, Node to) {
			classBindings.put(name, to);
		}

		public boolean hasPrimitiveBindings(String name) {
			return primitiveBindings.containsKey(name);
		}

		public JSType getPrimitiveBindingType(String name) {
			Node binding = primitiveBindings.get(name);
			if (binding != null) {
				return binding.getJSType();
			}
			return null;
		}

		public Node getPrimitiveBinding(String name) {
			return primitiveBindings.get(name);
		}

		public Node getClassBindings(String name) {
			return classBindings.get(name);
		}

		public boolean isRegistered(String name) {
			return classBindings.containsKey(name) || primitiveBindings.containsKey(name);
		}
	}


	private final class InstaniationProcessor extends AbstractPostOrderCallback {

		private class InjectionGetterProcessor extends AbstractPostOrderCallback {
			@Override
			public void visit(NodeTraversal t, Node n, Node parent) {
				if (n.isCall()) {
					Node child = n.getFirstChild();
					if (child.isGetProp() && child.getQualifiedName().equals(GET_INJECTION_CALL)) {
						Node nameNode = child.getNext();
						if (nameNode != null && nameNode.isString()) {
							processGetter(t, n, nameNode.getString());
						}
					}
				}
			}

			private void processGetter(NodeTraversal t, Node n, String bindingName) {
				if (bindingRegistry.isRegistered(bindingName)) {
					Node arg = null;
					if (bindingRegistry.hasPrimitiveBindings(bindingName)) {
						arg = createPrimitiveArgument(bindingName);
					} else {
						Node classEntity = bindingRegistry.getClassBindings(bindingName);
						if (classEntity != null) {
							ClassInjectionInfo classInjectionInfo =
								classInjectionInfoRegistry.getInfo(classEntity.getQualifiedName());
							if (classInjectionInfo != null) {
								arg = createInstaniationCall(t, classEntity.cloneTree(), classInjectionInfo, true);
								if (!classInjectionInfo.hasProvider() && !classInjectionInfo.isSingleton()) {
									arg = createInstaniationScope(t, "", arg, classInjectionInfo);
								}
							}
						}
					}
					if (arg != null) {
						n.getParent().replaceChild(n, arg);
					} else {
						n.getParent().replaceChild(n, new Node(Token.NULL));
					}
				}
			}
		}

		
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
					boolean isCreateSetter = this.isCreateSetter(classInjectionInfo, n);
					Node newcall = createInstaniationCall(t, child.cloneTree(), classInjectionInfo, !isCreateSetter);
					if (!classInjectionInfo.hasProvider() && isCreateSetter) {
						newcall = createInstaniationScope(t, child.getQualifiedName(), newcall, classInjectionInfo);
					}
					n.getParent().replaceChild(n, newcall);
					compiler.reportCodeChange();
				} else {
					t.report(child, MESSAGE_CLASS_NOT_FOUND);
				}
			}
		}

		private boolean isCreateSetter(ClassInjectionInfo classInjectionInfo, Node n) {
			if (classInjectionInfo.isSingleton()) {
				while (n != null) {
					if (n.isAssign()) {
						String name = n.getFirstChild().getQualifiedName();
						if (name != null && name.indexOf(GET_INSTANCE_MIRROR) != -1) {
							return true;
						}
					}
					n = n.getParent();
				}
				return false;
			}
			return true;
		}
		
		private Node createInstaniationCall(NodeTraversal t, Node n, ClassInjectionInfo classInjectionInfo, boolean isMirror) {
			Node newcall;
			Node provider = classInjectionInfo.getProvider();
			if (provider != null) {
				NodeTraversal.traverse(compiler, provider, new InjectionGetterProcessor());
				newcall = new Node(Token.CALL, provider.cloneTree());
			} else if (classInjectionInfo.isSingleton()) {
				Node getInstance = new Node(Token.GETPROP, n, isMirror? Node.newString(GET_INSTANCE_MIRROR) : Node.newString(SINGLETON_CALL));
				newcall = new Node(Token.CALL, getInstance);
			} else {
				newcall = new Node(Token.NEW, n);
			}
			newcall.copyInformationFromForTree(n);
			List<String> targets = classInjectionInfo.getConstructorArguments();
			createArguments(t, n.getQualifiedName(), null, targets, newcall, classInjectionInfo.getJSDocInfo());
			return newcall;
		}

		private Node createInstaniationScope(NodeTraversal t, String qualifiedName, Node newcall, ClassInjectionInfo classInjectionInfo) {
			classInjectionInfo.setInstaniationFlag(true);
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
						createArguments(t,
										qualifiedName,
										target,
										prototypeMemberInfo.getArgumentsList(),
										methodCall,
										prototypeMemberInfo.getJSDocInfo());
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

		private void createArguments(NodeTraversal t,
									 String qualifiedName,
									 String methodName,
									 List<String> targets, Node methodcall, JSDocInfo jsDocInfo) {
			if (targets != null) {
				for (String target : targets) {
					if (bindingRegistry.isRegistered(target)) {
						Node arg = null;
						if (bindingRegistry.hasPrimitiveBindings(target)) {
							JSType type = bindingRegistry.getPrimitiveBindingType(target);
							if (jsDocInfo != null) {
								checkPrimitiveTypes(t,
													target,
													qualifiedName,
													bindingRegistry.getPrimitiveBinding(target),
													jsDocInfo.getParameterType(target),
													type);
							}
							arg = createPrimitiveArgument(target);
						} else {
							Node classEntity = bindingRegistry.getClassBindings(target);
							if (classEntity != null) {
								ClassInjectionInfo classInjectionInfo =
									classInjectionInfoRegistry.getInfo(classEntity.getQualifiedName());
								if (classInjectionInfo != null) {
									if (jsDocInfo != null) {
										String fqMethodName =
											(methodName != null)? qualifiedName + ".prototype." + methodName : qualifiedName;
										checkClassType(t,
													   target,
													   fqMethodName,
													   classEntity,
													   jsDocInfo.getParameterType(target),
													   classInjectionInfo);
									}
									arg = createInstaniationCall(t, classEntity.cloneTree(), classInjectionInfo, true);
									if (!classInjectionInfo.hasProvider() && !classInjectionInfo.isSingleton()) {
										arg = createInstaniationScope(t, qualifiedName, arg, classInjectionInfo);
									}
								}
							}
						}
						if (arg != null) {
							methodcall.addChildToBack(arg);
						} else {
							methodcall.addChildToBack(new Node(Token.NULL));
						}
					} else {
						methodcall.addChildToBack(new Node(Token.NULL));
					}
				}
			}
		}
	}


	private void checkPrimitiveTypes(NodeTraversal t,
									 String bindingName,
									 String qualifiedName,
									 Node binding,
									 JSTypeExpression jsTypeExpression,
									 JSType type) {
		
		if (jsTypeExpression != null && type != null) {
			Node typeRoot = jsTypeExpression.getRoot();
			if (typeRoot != null) {
				JSType jsType = typeRoot.getJSType();
				if (!type.canCastTo(jsType)) {
					t.report(binding,
							 TYPE_MISMATCH_WARNING,
							 bindingName,
							 qualifiedName,
							 type.toString(),
							 jsType.toString());
				}
			}
		}
	}


	private void checkClassType(NodeTraversal t,
								String bindingName,
								String qualifiedName,
								Node binding,
								JSTypeExpression jsTypeExpression,
								ClassInjectionInfo info) {
		if (jsTypeExpression != null) {
			Node typeRoot = jsTypeExpression.getRoot();
			if (typeRoot != null) {
				JSType parameterType = typeRoot.getJSType();
				if (parameterType != null) {
					JSType jsType = info.getJSType();
					if (jsType.isFunctionType()) {
						FunctionType fnType = (FunctionType)jsType;
						JSType instanceType = fnType.getInstanceType();
						if (!instanceType.canCastTo(parameterType) && !instanceType.isEquivalentTo(parameterType)) {
							t.report(binding,
									 TYPE_MISMATCH_WARNING,
									 bindingName,
									 qualifiedName,
									 parameterType.toString(),
									 instanceType.toString());
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
				ConstructorNamingProcessor namingProcessor = new ConstructorNamingProcessor(n, constructorScopeChecker);
				String name = namingProcessor.getName();
				Node function = namingProcessor.getNode();
				if (name != null) {
					List<String> parsedArgumentsList = new ConstructorArgumentsParser(function).parse();
					ClassInjectionInfo classInjectionInfo = new ClassInjectionInfo(name, parsedArgumentsList, function);
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
		private Node constructor;
		private String className;
		private boolean isSingleton;
		private boolean isInstaniated;
		private List<String> argumentsList;
		private JSType constructorType;
		private JSDocInfo constructorDocInfo;
		private List<String> injectionTargets;
		private HashMap<String, PrototypeMemberInfo> prototypeMemberInfos = new HashMap<String, PrototypeMemberInfo>();
		private Node provider;
		private String superClass;

		public ClassInjectionInfo(String className,
								  List<String> constructorArgumentsList,
								  Node constructor) {
			this.className = className;
			this.argumentsList = constructorArgumentsList;
			this.constructor = constructor;
			this.constructorType = constructor.getJSType();
			this.isInstaniated = false;
		}

		public void addPrototypeInfo(PrototypeMemberInfo prototypeMemberInfo) {
			this.prototypeMemberInfos.put(prototypeMemberInfo.getMethodName(),
										  prototypeMemberInfo);
		}


		public Node getConstructorNode() {
			return this.constructor;
		}
		
		
		public JSType getJSType() {
			return this.constructorType;
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

		public void setProvider(Node provider) {
			this.provider = provider;
		}

		public void setInstaniationFlag(boolean isInstaniate) {
			this.isInstaniated = isInstaniate;
		}

		public boolean isInstaniated() {
			return this.isInstaniated;
		}

		public Node getProvider() {
			return this.provider;
		}

		public boolean hasProvider() {
			return this.provider != null;
		}

		public String getClassName() {
			return this.className;
		}

		public String getSuperClass() {
			return this.superClass;
		}

		public void setInjectionTargets(List<String> injectionTargets) {
			this.injectionTargets = injectionTargets.size() > 0? injectionTargets : null;
		}

		public void setSingleton(boolean isSingleton) {
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
		JSDocInfo jsDocInfo;
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

		public void setJSDocInfo(JSDocInfo jsDocInfo) {
			this.jsDocInfo = jsDocInfo;
		}

		public JSDocInfo getJSDocInfo() {
			return this.jsDocInfo;
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

		public PrototypeMemberParser(Node assignNode) {
			this.assignNode = assignNode;
			this.targetNode = this.assignNode.getFirstChild().getNext();
			if (this.targetNode.isFunction()) {
				isFunction = true;
			} else {
				isFunction = false;
			}
		}
		
		public void parse() {
			String qualifiedName = this.assignNode.getFirstChild().getQualifiedName();
			String className = parseMethodBelongedClassName(qualifiedName);
			if (this.isFunction) {
				String methodName = parseMethodName(qualifiedName);
				ConstructorArgumentsParser parser = new ConstructorArgumentsParser(this.targetNode);
				List<String> argumentsList = parser.parse();
				addInfo(methodName, className, argumentsList, this.targetNode.getJSDocInfo());
			} else if (this.targetNode.isObjectLit()) {
				Node child = this.targetNode.getFirstChild();
				Node function;
				String propertyName;
				for (;child != null; child = child.getNext()) {
					if (child.isStringKey()) {
						propertyName = child.getString();
						function = child.getFirstChild();
						if (function.isFunction()) {
							ConstructorArgumentsParser parser = new ConstructorArgumentsParser(function);
							List<String> argumentsList = parser.parse();
							addInfo(propertyName, className, argumentsList, function.getJSDocInfo());
						}
					}
				}
			}
		}

		private void addInfo(String methodName,
							 String className,
							 List<String> argumentsList,
							 JSDocInfo jsDocInfo) {
			PrototypeMemberInfo prototypeMemberInfo = new PrototypeMemberInfo(methodName,
																			  argumentsList);
			prototypeMemberInfo.setJSDocInfo(jsDocInfo);
			ClassInjectionInfo classInjectionInfo = classInjectionInfoRegistry.getInfo(className);
			if (classInjectionInfo != null) {
				classInjectionInfo.addPrototypeInfo(prototypeMemberInfo);
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
		NodeTraversal.traverse(compiler, root, new ConstructorInjectionProcessor());
		NodeTraversal.traverse(compiler, root, new SetterInjectionProcessor());
		NodeTraversal.traverse(compiler, root, new BindingProcessor());
		NodeTraversal.traverse(compiler, root, new InstaniationProcessor());
	}

	private static boolean shouldParsePrototype(Node assignNode, ClassInjectionInfoRegistry classInjectionInfoRegistry) {
		String name = parseMethodBelongedClassName(assignNode.getFirstChild().getQualifiedName());
		Node targetNode = assignNode.getFirstChild().getNext();
		if (name != null && (targetNode.isFunction() || targetNode.isObjectLit())) {
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
