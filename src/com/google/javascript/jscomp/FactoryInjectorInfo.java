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

  private List<InjectInfo> InjectInfoList = Lists.newArrayList();

  private Map<String, Node> providerCallMap = Maps.newHashMap();

  public void putTypeInfo(TypeInfo typeInfo) {
    this.typeInfoMap.put(typeInfo.getName(), typeInfo);
  }


  public ArrayListMultimap<String, TypeInfo> getTypeInfoMap() {
    return this.typeInfoMap;
  }


  public void addInjectInfo(InjectInfo injectInfo) {
    this.InjectInfoList.add(injectInfo);
  }


  public List<InjectInfo> getInjectInfoList() {
    return this.InjectInfoList;
  }

  public void putProviderCall(String s, Node n) {
    providerCallMap.put(s, n);
  }
  
  public Map<String, Node> getProviderCallMap() {
    return this.providerCallMap;
  }

  public static final class InjectInfo {
    private Node node;

    private boolean isInjectOnce = false;


    public InjectInfo(Node node, boolean isInjectOnce) {
      this.node = node;
      this.isInjectOnce = isInjectOnce;
    }


    public Node getNode() {
      return this.node;
    }


    public boolean isInjectOnce() {
      return this.isInjectOnce;
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
