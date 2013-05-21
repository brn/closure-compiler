package com.google.javascript.jscomp;

import java.util.List;
import java.util.Map;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.javascript.rhino.JSDocInfo;
import com.google.javascript.rhino.Node;

public class FactoryInjectorInfo {

  private ArrayListMultimap<String, TypeInfo> typeInfoMap = ArrayListMultimap.create();
  private List<NewWithInfo> newWithInfoList = Lists.newArrayList();
  private List<BinderInfo> binderInfoList = Lists.newArrayList();
  
  public void putTypeInfo(TypeInfo typeInfo) {
    this.typeInfoMap.put(typeInfo.getName(), typeInfo);
  }
  
  public ArrayListMultimap<String, TypeInfo> getTypeInfoMap() {
    return this.typeInfoMap;
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
  
  
  public static final class BindedConstructorInfo {
    private boolean isSingleton;
    private Node node;
    private String name;
    
    public BindedConstructorInfo(boolean isSingleton, Node node, String name) {
      this.isSingleton = isSingleton;
      this.node = node;
      this.name = name;
    }
    
    public boolean isSingleton() {
      return isSingleton;
    }
    
    public Node getNode() {
      return node;
    }
    
    public String getName() {
      return name;
    }
  }
  
  public static final class BindedKeyInfo {
    private Node node;
    private String name;
    
    public BindedKeyInfo(Node node, String name) {
      this.node = node;
      this.name = name;
    }
    
    public Node getNode() {
      return node;
    }
    
    public String getName() {
      return name;
    }
  }
  
  public static final class BinderInfo {
    private Node node;
   
    
    private Map<BindedKeyInfo, BindedConstructorInfo> bindingInfoMap = Maps.newHashMap(); 
    
    public BinderInfo(Node node) {
      this.node = node;
    }
    
    public Node getNode() {
      return this.node;
    }
    
    public void putBindingInfo(BindedKeyInfo keyInfo, BindedConstructorInfo constructorInfo) {
      this.bindingInfoMap.put(keyInfo, constructorInfo);
    }
    
    public Map<BindedKeyInfo, BindedConstructorInfo> getBindingInfoMap() {
      return this.bindingInfoMap;
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

    private String name;
    
    private String aliasName;
    
    private JSDocInfo jsDocInfo;
    
    private boolean hasInstanceFactory = false;
    
    private boolean isAlias = false;

    public TypeInfo(String name, Node constructorNode, JSDocInfo jsDocInfo) {
      this.constructorNode = constructorNode;
      this.name = name;
      this.jsDocInfo = jsDocInfo;
    }


    /**
     * @return the name
     */
    public String getName() {
      return name;
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
    
    public JSDocInfo getJSDocInfo() {
      return this.jsDocInfo;
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
}
