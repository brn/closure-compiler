package com.google.javascript.jscomp;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.javascript.rhino.Node;

final class CampModuleTransformInfo {
  private Map<String, ModuleInfo> moduleInfoMap = Maps.newHashMap();

  private int id = 0;


  public void putModuleInfo(String sourceFileName, ModuleInfo moduleInfo) {
    this.moduleInfoMap.put(sourceFileName, moduleInfo);
  }


  public ModuleInfo getModuleInfo(String sourceFileName) {
    return this.moduleInfoMap.get(sourceFileName);
  }


  public boolean hasModuleInfo(String sourceFileName) {
    return this.moduleInfoMap.containsKey(sourceFileName);
  }


  public Map<String, ModuleInfo> getModuleInfoMap() {
    return this.moduleInfoMap;
  }


  public ModuleInfo createModuleInfo(
      String moduleName,
      String moduleId,
      Node moduleCallNode,
      Node nra) {
    return new ModuleInfo(moduleName, moduleId, moduleCallNode, nra);
  }


  private static final class VarRenamePair {
    private Node baseDeclaration;

    private Node targetNode;


    public VarRenamePair(Node baseDeclaration, Node targetNode) {
      this.baseDeclaration = baseDeclaration;
      this.targetNode = targetNode;
    }


    public Node getBaseDeclaration() {
      return baseDeclaration;
    }


    public Node getTargetNode() {
      return targetNode;
    }
  }


  final class ModuleInfo {
    private Node moduleCallNode;

    private Node nra;

    private String moduleName;

    private String moduleId;

    private Node main;

    private List<Node> usingCallList = Lists.newArrayList();

    private Set<Node> exportsSet = Sets.newLinkedHashSet();

    private Map<String, String> aliasMap = Maps.newHashMap();

    private Map<String, String> varRenameMap = Maps.newHashMap();

    private Set<Node> renameVarBaseDeclaration = Sets.newHashSet();

    private List<VarRenamePair> renameTargetList = Lists.newArrayList();

    private Set<Node> exportedTypeSet = Sets.newHashSet();

    private Set<Node> aliasTypeSet = Sets.newHashSet();

    private List<Node> aliasVarList = Lists.newArrayList();

    private Set<Node> localTypeSet = Sets.newHashSet();


    private ModuleInfo(String moduleName, String moduleId, Node moduleCallNode, Node nra) {
      this.moduleCallNode = moduleCallNode;
      this.moduleName = moduleName;
      this.moduleId = moduleId + "_" + id;
      this.nra = nra;
      id++;
    }


    public Node getModuleCallNode() {
      return this.moduleCallNode;
    }


    public Node getNamespaceReferenceArgument() {
      return this.nra;
    }


    public void addUsingCall(Node usingCall) {
      this.usingCallList.add(usingCall);
    }


    public List<Node> getUsingCallList() {
      return this.usingCallList;
    }


    public String getModuleName() {
      return moduleName;
    }


    public String getModuleId() {
      return moduleId;
    }


    public Node getMain() {
      return main;
    }


    public void setMain(Node main) {
      this.main = main;
    }


    public void addExports(Node exports) {
      this.exportsSet.add(exports);
    }


    public Set<Node> getExportsSet() {
      return this.exportsSet;
    }


    public boolean hasExports(Node n) {
      return this.exportsSet.contains(n);
    }


    public void putAliasMap(String name, String qualifiedName) {
      this.aliasMap.put(name, qualifiedName);
    }


    public String getAliasName(String name) {
      return this.aliasMap.get(name);
    }


    public void putRenamedVar(String before, String after) {
      this.varRenameMap.put(before, after);
    }


    public String getRenamedVar(String before) {
      return this.varRenameMap.get(before);
    }


    public void addRenameTarget(Node declaration, Node varName) {
      VarRenamePair varRenamePair = new VarRenamePair(declaration, varName);
      renameTargetList.add(varRenamePair);
    }


    public List<VarRenamePair> getRenameTargetList() {
      return this.renameTargetList;
    }


    public void addAliasType(Node type) {
      this.aliasTypeSet.add(type);
    }


    public Set<Node> getAliasTypeSet() {
      return this.aliasTypeSet;
    }


    public List<Node> getAliasVarList() {
      return aliasVarList;
    }


    public void addAliasVar(Node aliasVar) {
      this.aliasVarList.add(aliasVar);
    }


    public boolean getAliasVar(Node aliasVar) {
      return this.aliasVarList.contains(aliasVar);
    }


    public void addExportedType(Node type) {
      this.exportedTypeSet.add(type);
    }


    public Set<Node> getExportedTypeSet() {
      return this.exportedTypeSet;
    }


    public boolean hasExportedType(Node n) {
      return this.exportedTypeSet.contains(n);
    }


    public void addLocalType(Node localTypeNode) {
      this.localTypeSet.add(localTypeNode);
    }


    public boolean hasLocalType(Node localTypeNode) {
      return this.localTypeSet.contains(localTypeNode);
    }


    public Set<Node> getLocalTypeSet() {
      return this.localTypeSet;
    }


    public void addRenameVarBaseDeclaration(Node base) {
      this.renameVarBaseDeclaration.add(base);
    }


    public boolean isRenameVarBaseDeclaration(Node base) {
      return this.renameVarBaseDeclaration.contains(base);
    }
  }
}
