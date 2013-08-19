package com.google.javascript.jscomp;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.common.collect.Sets;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.javascript.jscomp.NodeTraversal.AbstractPostOrderCallback;
import com.google.javascript.rhino.Node;

final class MixinInfo {

  private Node node;

  private String type;

  private List<String> traits;

  private Node topNode;

  private Map<String, Node> excludesMap;


  public MixinInfo(
      Node node,
      Node topNode,
      String type,
      List<String> traits,
      Map<String, Node> excludesMap) {
    this.node = node;
    this.type = type;
    this.traits = traits;
    this.topNode = topNode;
    this.excludesMap = excludesMap;
  }


  /**
   * @return the node
   */
  public Node getNode() {
    return node;
  }


  /**
   * @return the type
   */
  public String getType() {
    return type;
  }


  /**
   * @return the traits
   */
  public List<String> getTraits() {
    return traits;
  }


  public Node getTopNode() {
    return this.topNode;
  }


  /**
   * @return the excludesMap
   */
  public Map<String, Node> getExcludesMap() {
    return excludesMap;
  }


  static final class TraitImplementationInfo {
    private final class ImplementationInfo {
      public boolean isImplmented = false;

      public TraitProperty prop;


      public ImplementationInfo(TraitProperty prop) {
        this.prop = prop;
      }
    }

    private Map<String, ImplementationInfo> implementationMap = Maps.newHashMap();

    private Map<String, TraitProperty> conflictionGuard = Maps.newHashMap();


    public void addImplementationInfo(TraitProperty prop) {
      String name = prop.getName();
      implementationMap.put(name, new ImplementationInfo(prop));
    }


    public boolean checkConfliction(TraitProperty prop, Node errorNode) {
      String name = prop.getName();
      if (conflictionGuard.containsKey(name)) {
        CampUtil.report(errorNode, MixinInfoConst.MESSAGE_DETECT_UNRESOLVED_METHOD,
            prop.getName(), prop.getCurrentHolderName(),
            conflictionGuard.get(name).getCurrentHolderName());
        return false;
      }
      conflictionGuard.put(name, prop);
      return true;
    }


    public void markAsImplemented(TraitProperty prop) {
      String name = prop.getName();
      ImplementationInfo info = implementationMap.get(name);
      if (info != null) {
        info.isImplmented = true;
      }
    }


    public void checkUnimplementedProperty(Node errorNode) {
      for (String name : implementationMap.keySet()) {
        ImplementationInfo iinfo = implementationMap.get(name);
        if (!iinfo.isImplmented) {
          CampUtil.report(errorNode, MixinInfoConst.MESSAGE_REQUIRED_PROPERTY_IS_NOT_IMPLMENTED,
              name, iinfo.prop.getCurrentHolderName(), iinfo.prop.getRefName());
        }
      }
    }
  }


  static final class TraitProperty implements Cloneable {

    private Node valueNode;

    private String name;

    private String refName;

    private String lastHolderName;

    private Node definitionPosition;

    private String thisType = "Object";

    private String currentHolderName;

    private Set<TraitInfo> propagationInfo = Sets.newHashSet();

    private boolean specialized = false;

    private boolean implicit = false;

    private boolean require = false;

    private TraitInfo holder;

    private boolean function = false;

    private boolean thisAccess = false;


    public TraitProperty(
        String name,
        String refName,
        Node valueNode,
        Node definitionPosition,
        TraitInfo holder,
        boolean thisAccess) {
      this.name = name;
      this.refName = refName;
      this.lastHolderName = refName;
      this.currentHolderName = refName;
      this.valueNode = valueNode;
      this.definitionPosition = definitionPosition;
      this.holder = holder;
      this.thisAccess = thisAccess;
      this.function = valueNode.isFunction();
    }


    public Node getValueNode() {
      return valueNode;
    }


    public String getName() {
      return name;
    }


    public String getRefName() {
      return refName;
    }


    public Node getDefinitionPosition() {
      return definitionPosition;
    }


    public String getThisType() {
      return thisType;
    }


    public void setThisType(String thisType) {
      this.thisType = thisType;
    }


    public TraitInfo getHolder() {
      return this.holder;
    }


    public void markAsSpecialization() {
      this.specialized = true;
    }


    public boolean isSpecialized() {
      return this.specialized;
    }


    public void setImplicit(boolean isImplicit) {
      this.implicit = isImplicit;
    }


    public boolean isImplicit() {
      return this.implicit;
    }


    public boolean isRequire() {
      return require;
    }


    public void markAsRequire() {
      this.require = true;
    }


    public void setCurrentHolderName(String name) {
      this.lastHolderName = this.currentHolderName;
      this.currentHolderName = name;
    }


    public String getCurrentHolderName() {
      return this.currentHolderName;
    }


    public String getLastHolderName() {
      return this.lastHolderName;
    }


    public boolean isPropagated(TraitInfo tinfo) {
      return propagationInfo.contains(tinfo);
    }


    public void markAsPropagated(TraitInfo tinfo) {
      this.propagationInfo.add(tinfo);
    }


    /**
     * @return the function
     */
    public boolean isFunction() {
      return function;
    }


    /**
     * @return the thisAccess
     */
    public boolean isThisAccess() {
      return thisAccess;
    }


    @Override
    public Object clone() {
      try {
        TraitProperty ret = (TraitProperty) super.clone();
        ret.propagationInfo = Sets.newHashSet();
        return ret;
      } catch (CloneNotSupportedException e) {
        throw new InternalError(e.toString());
      }
    }
  }


  static final class TraitInfo {
    private Node topNode;

    private String refName;

    private Node body;

    private Map<String, TraitProperty> properties = Maps.newHashMap();

    private List<String> requires = Lists.newArrayList();


    public TraitInfo(List<String> requires, Node topNode, Node body, String refName) {
      this.requires = requires;
      this.topNode = topNode;
      this.refName = refName;
      this.body = body;
      addImplicitProperties();
    }


    private void addImplicitProperties() {
      for (Node prop : body.children()) {
        String name = prop.getString();
        Node valueNode = prop.getFirstChild();
        boolean accessedToThis = false;
        if (valueNode.isFunction()) {
          ThisAccessChecker checker = new ThisAccessChecker();
          NodeTraversal.traverseRoots(CampUtil.getCompiler(), Lists.newArrayList(valueNode),
              checker);
          accessedToThis = checker.isThisAccess();
        }
        TraitProperty property = new TraitProperty(name, refName, valueNode, topNode, this,
            accessedToThis);
        property.setImplicit(true);
        Node value = prop.getFirstChild();
        if (value.isGetProp()) {
          String qname = value.getQualifiedName();
          if (qname.equals(MixinInfoConst.REQUIRE)) {
            property.markAsRequire();
          }
        }
        properties.put(name, property);
      }
    }


    /**
     * @return the topNode
     */
    public Node getTopNode() {
      return topNode;
    }


    /**
     * @return the refName
     */
    public String getRefName() {
      return refName;
    }


    /**
     * @return the body
     */
    public Node getBody() {
      return body;
    }


    public void addProperty(TraitProperty property, Node keyNode) {
      properties.put(property.getName(), property);
      CampUtil.reportCodeChange();
    }


    public Map<String, TraitProperty> getProperties() {
      return properties;
    }


    public List<String> getRequires() {
      return requires;
    }


    private static final class ThisAccessChecker extends AbstractPostOrderCallback {
      private boolean thisAccess = false;


      public void visit(NodeTraversal t, Node n, Node parent) {
        if (n.isThis()) {
          if (t.getScope().getDepth() == 1) {
            thisAccess = true;
          }
        }
      }


      public boolean isThisAccess() {
        return this.thisAccess;
      }
    }
  }
}
