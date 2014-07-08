package com.google.javascript.jscomp;

import java.util.List;
import java.util.Map;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.javascript.rhino.JSDocInfo;
import com.google.javascript.rhino.Node;

/**
 * This class holds informations that are referenced during FactoryInjector
 * process.
 * 
 * @author aono_taketoshi
 * 
 */
public class FactoryInjectorInfo {

  private ArrayListMultimap<String, TypeInfo> typeInfoMap = ArrayListMultimap.create();

  private List<InjectInfo> InjectInfoList = Lists.newArrayList();

  private Map<String, Node> providerCallMap = Maps.newHashMap();


  /**
   * Insert type information.
   * 
   * @param typeInfo
   *          The Type information.
   */
  public void putTypeInfo(TypeInfo typeInfo) {
    this.typeInfoMap.put(typeInfo.getName(), typeInfo);
  }


  /**
   * Return the type information map.
   * 
   * @return The type information map.
   */
  public ArrayListMultimap<String, TypeInfo> getTypeInfoMap() {
    return this.typeInfoMap;
  }


  /**
   * Add dependency injection information.
   * 
   * @param injectInfo
   *          The dependency injection information.
   */
  public void addInjectInfo(InjectInfo injectInfo) {
    this.InjectInfoList.add(injectInfo);
  }


  /**
   * Return the dependency injection information list.
   * 
   * @return The dependency injection information list.
   */
  public List<InjectInfo> getInjectInfoList() {
    return this.InjectInfoList;
  }


  /**
   * This class holds informations that is used to decide factory type.
   * 
   * @author aono_taketoshi
   * 
   */
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


  /**
   * Type information.
   * 
   * @author aono_taketoshi
   * 
   */
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


    /**
     * Return whether this type already has factory method or not.
     * 
     * @return true this type already has factory method, otherwise false.
     */
    public boolean hasInstanceFactory() {
      return this.hasInstanceFactory;
    }


    /**
     * Enable flag that decide whether this type has factory method or not.
     */
    public void setHasInstanceFactory() {
      this.hasInstanceFactory = true;
    }


    /**
     * Return JSDocInfo of the constructor node.
     * 
     * @return
     */
    public JSDocInfo getJSDocInfo() {
      return this.jsDocInfo;
    }


    /**
     * Check whether this type is alias of an existing type or not.
     * 
     * @return true if this type is alias of an existing type, otherwise false.
     */
    public boolean isAlias() {
      return this.isAlias;
    }


    /**
     * Enable flag that decide whether this type is alias of an existing type or
     * not.
     */
    public void setAlias() {
      this.isAlias = true;
    }


    /**
     * Set an alias type name.
     * 
     * @param name
     *          An alias type name.
     */
    public void setAliasName(String name) {
      this.aliasName = name;
    }


    /**
     * Return an alias type name.
     * 
     * @return An alias type name.
     */
    public String getAliasName() {
      return this.aliasName;
    }

  }
}
