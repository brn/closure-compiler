package com.google.javascript.jscomp;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.javascript.rhino.Node;

public class FactoryInjectorInfo {

  private ArrayListMultimap<String, TypeInfo> typeInfoMap = ArrayListMultimap.create();
  private List<ResolvePoints> resolvePointsList = Lists.newArrayList();
  private List<NewWithInfo> newWithInfoList = Lists.newArrayList();
  private List<BinderInfo> binderInfoList = Lists.newArrayList();
  private ArrayListMultimap<String, MethodInjectionInfo> methodInjectionInfoMap = ArrayListMultimap.create();
  
  public void putTypeInfo(TypeInfo typeInfo) {
    this.typeInfoMap.put(typeInfo.getName(), typeInfo);
  }
  
  public ArrayListMultimap<String, TypeInfo> getTypeInfoMap() {
    return this.typeInfoMap;
  }
  
  public void addResolvePoints(ResolvePoints resolvePoints) {
    this.resolvePointsList.add(resolvePoints);
  }
  
  public List<ResolvePoints> getResolvePointsList() {
    return this.resolvePointsList;
  }
  
  
  public void putMethodInjectionInfo(MethodInjectionInfo methodInjectionInfo) {
    this.methodInjectionInfoMap.put(methodInjectionInfo.getTypeName(), methodInjectionInfo);
  }
  
  public ArrayListMultimap<String, MethodInjectionInfo> getMethodInjectionInfo() {
    return this.methodInjectionInfoMap;
  }
  
  public void addNewWtihInfo(NewWithInfo newWithInfo) {
    this.newWithInfoList.add(newWithInfo);
  }
  
  public List<NewWithInfo> getNewWithInfoList() {
    return this.newWithInfoList;
  }
  
  public void addBinderInfo(BinderInfo binderInfo) {
    this.binderInfoList.add(binderInfo);
  }
  
  public List<BinderInfo> getBinderInfoList() {
    return this.binderInfoList;
  }
  
  public static final class BinderInfo {
    private Node node;
    
    private boolean isSingleton;
    
    public BinderInfo(Node node, boolean isSingleton) {
      this.node = node;
      this.isSingleton = isSingleton;
    }
    
    public Node getNode() {
      return this.node;
    }
    
    public boolean isSingleton() {
      return this.isSingleton;
    }
  }
  
  public static final class NewWithInfo {
    private Node node;
    
    public NewWithInfo(Node node) {
      this.node = node;
    }
    
    public Node getNode() {
      return this.node;
    }
  }
  
  
  public static class TypeInfo {
    private Node constructorNode;

    private List<MethodInjectionInfo> methodInjectionList = Lists.newArrayList();

    private String name;
    
    private boolean isAlias = false;
    
    private String aliasName;
    
    private boolean hasInstanceFactory = false;

    public TypeInfo(String name, Node constructorNode) {
      this.constructorNode = constructorNode;
      this.name = name;
    }


    /**
     * @return the name
     */
    public String getName() {
      return name;
    }


    /**
     * @return the methodInjectionList
     */
    public List<MethodInjectionInfo> getMethodInjectionList() {
      return methodInjectionList;
    }


    /**
     * @param methodInjectionList
     *          the methodInjectionList to set
     */
    public void setMethodInjectionList(List<MethodInjectionInfo> methodInjectionList) {
      this.methodInjectionList = methodInjectionList;
    }


    /**
     * @return the constructorNode
     */
    public Node getConstructorNode() {
      return constructorNode;
    }
    
    public boolean hasInstanceFactory() {
      return this.hasInstanceFactory;
    }
    
    public void setHasInstanceFactory() {
      this.hasInstanceFactory = true;
    }
    
    public boolean isAlias() {
      return this.isAlias;
    }
    
    public void setAlias() {
      this.isAlias = true;
    }
    
    public void setAliasName(String name) {
      this.aliasName = name;
    }
    
    public String getAliasName() {
      return this.aliasName;
    }
  }


  enum CallType {
    RESOLVE,
    RESOLVE_ONCE
  }


  public static class ResolvePoints {
    private Node resolveCallNode;

    private String typeName;

    private Node fromNode;

    private CallType callType;

    public ResolvePoints(Node resolveCallNode, String typeName, Node fromNode, CallType callType) {
      this.resolveCallNode = resolveCallNode;
      this.typeName = typeName;
      this.fromNode = fromNode;
      this.callType = callType;
    }

    /**
     * @return the resolveCallNode
     */
    public Node getResolveCallNode() {
      return resolveCallNode;
    }


    /**
     * @return the typeName
     */
    public String getTypeName() {
      return typeName;
    }


    /**
     * @return the fromNode
     */
    public Node getFromNode() {
      return fromNode;
    }


    /**
     * @return the callType
     */
    public CallType getCallType() {
      return callType;
    }

  }
  
  public static final class MethodInjectionInfo {
    private String methodName;
    private List<String> parameterList = Lists.newArrayList();
    private String name;
    private Node node;
    
    public MethodInjectionInfo(String name, String methodName, Node n) {
      this.name = name;
      this.methodName = methodName;
      this.node = n;
    }
    
    public String getTypeName() {
      return this.name;
    }
    
    public String getMethodName() {
      return this.methodName;
    }
    
    public void setParameterList(List<String> parameterList) {
      this.parameterList = parameterList;
    }
    
    public List<String> getParameterList() {
      return this.parameterList;
    }
    
    public Node getNode() {
      return this.node;
    }
  }
}
