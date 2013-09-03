package com.google.javascript.jscomp;

import java.util.List;
import java.util.Map;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.javascript.rhino.JSDocInfo;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.jstype.JSType;

public class CampTypeInfo {

  public final Map<String, TypeInfo> typeInfoMap = Maps.newHashMap();

  public final Multimap<String, PrototypeProperty> prototypeInfoMap = HashMultimap.create();


  public void putTypeInfo(TypeInfo typeInfo) {
    typeInfoMap.put(typeInfo.getName(), typeInfo);
  }


  public Map<String, TypeInfo> getTypeInfoMap() {
    return this.typeInfoMap;
  }


  public void putPrototypeInfo(PrototypeProperty prop) {
    prototypeInfoMap.put(prop.getConstructorName(), prop);
  }


  public Multimap<String, PrototypeProperty> getPrototypeInfoMap() {
    return prototypeInfoMap;
  }


  static final class PrototypeProperty implements Cloneable {
    private boolean implicit = true;

    private String constructorName;

    private String name;

    private Node value;

    private JSDocInfo jsDocInfo;

    private JSType jsType;


    public PrototypeProperty(String constructorName, String name, Node value, JSDocInfo jsDocInfo,
        boolean implicit) {
      this.constructorName = constructorName;
      this.name = name;
      this.value = value;
      this.implicit = implicit;
    }


    /**
     * @return the constructorName
     */
    public String getConstructorName() {
      return constructorName;
    }


    /**
     * @return the implicit
     */
    public boolean isImplicit() {
      return implicit;
    }


    /**
     * @return the jsType
     */
    public JSType getJsType() {
      return jsType;
    }


    /**
     * @param jsType
     *          the jsType to set
     */
    public void setJsType(JSType jsType) {
      this.jsType = jsType;
    }


    /**
     * @return the name
     */
    public String getName() {
      return name;
    }


    /**
     * @return the value
     */
    public Node getValue() {
      return value;
    }


    /**
     * @return the jsDocInfo
     */
    public JSDocInfo getJsDocInfo() {
      return jsDocInfo;
    }


    /**
     * @param jsDocInfo
     *          the jsDocInfo to set
     */
    public void setJsDocInfo(JSDocInfo jsDocInfo) {
      this.jsDocInfo = jsDocInfo;
    }


    @Override
    public Object clone() {
      try {
        PrototypeProperty ret = (PrototypeProperty) super.clone();
        ret.implicit = false;
        return ret;
      } catch (CloneNotSupportedException e) {
        throw new InternalError(e.toString());
      }
    }
  }


  static final class TypeInfo implements Cloneable {
    private String name;

    private Node constructor;

    private Map<String, PrototypeProperty> prototypes = Maps.newHashMap();

    private JSDocInfo jsDocInfo;

    private boolean ambiguous;

    private boolean inheritancePropagated;

    private List<String> mixins = Lists.newArrayList();


    public TypeInfo(String name, Node constructor, JSDocInfo jsDocInfo) {
      this.name = name;
      this.constructor = constructor;
      this.jsDocInfo = jsDocInfo;
    }


    public void addMixin(String refName) {
      mixins.add(refName);
    }


    public List<String> getMxins() {
      return mixins;
    }


    public String getName() {
      return this.name;
    }


    public Node getConstructorNode() {
      return this.constructor;
    }


    public JSDocInfo getJSDocInfo() {
      return this.jsDocInfo;
    }


    /**
     * @return the ambiguous
     */
    public boolean isAmbiguous() {
      return ambiguous;
    }


    /**
     * @return the prototypes
     */
    public Map<String, PrototypeProperty> getPrototypeMap() {
      return prototypes;
    }


    public void putPrototype(PrototypeProperty prop) {
      prototypes.put(prop.getName(), prop);
    }


    public Object clone() {
      TypeInfo typeInfo = null;
      try {
        typeInfo = (TypeInfo) super.clone();
      } catch (CloneNotSupportedException e) {
        throw new InternalError(e.toString());
      }
      return typeInfo;
    }


    /**
     * @return the inheritancePropagated
     */
    public boolean isInheritancePropagated() {
      return inheritancePropagated;
    }


    /**
     * @param inheritancePropagated
     *          the inheritancePropagated to set
     */
    public void setInheritancePropagated(boolean inheritancePropagated) {
      this.inheritancePropagated = inheritancePropagated;
    }

  }
}
