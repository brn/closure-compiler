package com.google.javascript.jscomp;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.javascript.rhino.JSDocInfo;
import com.google.javascript.rhino.JSDocInfoBuilder;
import com.google.javascript.rhino.Node;

/**
 * This class holds informations that are referenced from module transformer
 * during transforming camp style module to google closure library style module.
 * 
 * @author aono_taketoshi
 * 
 */
final class CampModuleTransformInfo {
  /**
   * The map that is used when searching a ModuleInfo from the source file name.
   */
  private Map<String, ModuleInfo> moduleInfoMap = Maps.newHashMap();

  /**
   * The module unique id that is used when rename the module local variables to
   * the global unique variables.
   */
  private int id = 0;


  /**
   * Put new ModuleInfo.
   * 
   * @param sourceFileName
   *          The source file name.
   * @param moduleInfo
   *          The ModuleInfo.
   */
  public void putModuleInfo(String sourceFileName, ModuleInfo moduleInfo) {
    this.moduleInfoMap.put(sourceFileName, moduleInfo);
  }


  /**
   * Get ModuleInfo from the source file name.
   * 
   * @param sourceFileName
   *          The filename of the searching ModuleInfo.
   * @return Target ModuleInfo.
   */
  public ModuleInfo getModuleInfo(String sourceFileName) {
    return this.moduleInfoMap.get(sourceFileName);
  }


  /**
   * Return true if ModuleInfo found otherwise false.
   * 
   * @param sourceFileName
   *          sourceFileName The filename of the searching ModuleInfo.
   * @return true if found otherwise false.
   */
  public boolean hasModuleInfo(String sourceFileName) {
    return this.moduleInfoMap.containsKey(sourceFileName);
  }


  /**
   * Return the map that has the source filenames and ModuleInfos.
   * 
   * @return The map.
   */
  public Map<String, ModuleInfo> getModuleInfoMap() {
    return this.moduleInfoMap;
  }


  /**
   * The interface of jsdoc rewriters.
   * 
   * @author aono_taketoshi
   * 
   */
  interface JSDocMutator {
    /**
     * Rewrite old JSDoc values to newly passed JSDoc value.
     * 
     * @param value
     *          New value of the target JSDoc item.
     */
    public void mutate(String value);


    /**
     * Return whether JSDoc is actually rewritten or not.
     * 
     * @return true if actually rewritten otherwise false.
     */
    public boolean isCodeChanged();


    /**
     * Return the old JSDoc type value.
     * 
     * @return The old JSDoc value.
     */
    public String getTypeString();
  }


  /**
   * The JSDoc rewriter of the type annotations. This class rewrite the specific
   * type annotations that are like 'inherits', 'extends', etc...
   * 
   * @author aono_taketoshi
   * 
   */
  static final class JSDocTypeInfoMutator implements JSDocMutator {
    /**
     * A Node that has target type annotations.
     */
    private Node node;

    /**
     * Flag that is used for whether actually rewritten or not.
     */
    private boolean isCodeChanged = false;

    /**
     * An old JSDoc type value.
     */
    private String type;


    /**
     * Constructor.
     * 
     * @param node
     *          The target node.
     * @param type
     *          An old JSDoc type value.
     */
    public JSDocTypeInfoMutator(Node node, String type) {
      Preconditions.checkNotNull(node);
      Preconditions.checkNotNull(type);
      this.node = node;
      this.type = type;
    }


    @Override
    public void mutate(String value) {
      Preconditions.checkNotNull(value);
      this.node.setString(value);
      isCodeChanged = true;
    }


    @Override
    public boolean isCodeChanged() {
      return isCodeChanged;
    }


    @Override
    public String getTypeString() {
      return this.type;
    }
  }


  /**
   * Rewriter of the 'lends' annotations.
   * 
   * @author aono_taketoshi
   * 
   */
  static final class JSDocLendsInfoMutator implements JSDocMutator {
    /**
     * A Node that has target type annotations.
     */
    private Node node;

    /**
     * Flag that is used for whether actually rewritten or not.
     */
    private boolean isCodeChanged = false;

    /**
     * An old JSDoc type value.
     */
    private String type;


    /**
     * Constructor.
     * 
     * @param node
     *          The target node.
     * @param type
     *          An old JSDoc type value.
     */
    public JSDocLendsInfoMutator(Node node, String type) {
      Preconditions.checkNotNull(node);
      Preconditions.checkNotNull(type);
      this.node = node;
      this.type = type;
    }


    @Override
    public void mutate(String value) {
      Preconditions.checkNotNull(value);
      JSDocInfoBuilder builder = new JSDocInfoBuilder(false);
      builder.recordLends(value);
      JSDocInfo info = builder.build(node);
      node.setJSDocInfo(info);
      isCodeChanged = true;
    }


    @Override
    public boolean isCodeChanged() {
      return isCodeChanged;
    }


    @Override
    public String getTypeString() {
      return this.type;
    }
  }


  /**
   * Create ModuleInfo.
   * 
   * @param moduleName
   *          Current module name.
   * @param moduleId
   *          Current module id.
   * @param moduleCallNode
   *          The 'camp.module(...)' calll node.
   * @param nra
   *          The namespace referenced arguments that is argument 'x' of
   *          following code. 'camp.module(..., function(x){...})'.
   * @return
   */
  public ModuleInfo createModuleInfo(
      String moduleName,
      String moduleId,
      Node moduleCallNode,
      Node nra) {
    return new ModuleInfo(moduleName, moduleId, moduleCallNode, nra);
  }


  /**
   * This class holds variable declaration node and used node.
   * 
   * @author aono_taketoshi
   * 
   */
  static final class VarRenamePair {
    /**
     * The declaration node.
     */
    private Node baseDeclaration;

    /**
     * The used node.
     */
    private Node targetNode;


    /**
     * Constructor.
     * 
     * @param baseDeclaration
     *          The declaration node.
     * @param targetNode
     *          The used node.
     */
    public VarRenamePair(Node baseDeclaration, Node targetNode) {
      this.baseDeclaration = baseDeclaration;
      this.targetNode = targetNode;
    }


    /**
     * Return the variable declaration node.
     * 
     * @return The variable declaration node.
     */
    public Node getBaseDeclaration() {
      return baseDeclaration;
    }


    /**
     * Return the variable used node.
     * 
     * @return The variable used node.
     */
    public Node getTargetNode() {
      return targetNode;
    }
  }


  /**
   * This class holds information of the declared types.
   * 
   * @author aono_taketoshi
   * 
   */
  static final class TypeInfo {
    /**
     * The type name.
     */
    private String name;

    /**
     * Type declaration node(maybe constructor).
     */
    private Node constructor;

    /**
     * The JSDoc annotation that is attached to the node.
     */
    private JSDocInfo jsDocInfo;


    /**
     * Constructor.
     * 
     * @param name
     *          The type name.
     * @param constructor
     *          The type declared node.
     * @param jsDocInfo
     *          The JSDoc annotation that attached to the node.
     */
    public TypeInfo(String name, Node constructor, JSDocInfo jsDocInfo) {
      this.name = name;
      this.constructor = constructor;
      this.jsDocInfo = jsDocInfo;
    }


    /**
     * Return the type name.
     * 
     * @return The type name.
     */
    public String getName() {
      return this.name;
    }


    /**
     * Return the type declared node.
     * 
     * @return The type declared node.
     */
    public Node getConstructorNode() {
      return this.constructor;
    }


    /**
     * Return JSDoc attached to the node.
     * 
     * @return The JSDoc attached to the node.
     */
    public JSDocInfo getJSDocInfo() {
      return this.jsDocInfo;
    }
  }


  /**
   * This class holds local aliased type information.
   * 
   * @author aono_taketoshi
   * 
   */
  static final class LocalAliasInfo {
    /**
     * Aliased node.
     */
    private Node node;

    /**
     * The assignment left hand side node.
     */
    private String lvalue;

    /**
     * The assignment right hand side node.
     */
    private String rvalue;


    /**
     * Constructor.
     * 
     * @param node
     *          The aliased node.
     * @param lvalue
     *          The assignment left hand side value.
     * @param rvalue
     *          The assignment right hand side value.
     */
    public LocalAliasInfo(Node node, String lvalue, String rvalue) {
      this.node = node;
      this.lvalue = lvalue;
      this.rvalue = rvalue;
    }


    /**
     * @return the node
     */
    public Node getNode() {
      return node;
    }


    /**
     * @return the lvalue
     */
    public String getLvalue() {
      return lvalue;
    }


    /**
     * @return the rvalue
     */
    public String getRvalue() {
      return rvalue;
    }

  }


  /**
   * Represent each camp style module declarations like
   * "camp.module(..., function(){...})".
   * 
   * @author aono_taketoshi
   * 
   */
  final class ModuleInfo {
    /**
     * The 'camp.module' call node.
     */
    private Node moduleCallNode;

    /**
     * The namespace referenced argument that is argument x of the following
     * code 'camp.module(..., function(x) {...});'.
     */
    private Node nra;

    /**
     * The module name of the camp module that is the string value of the
     * following code 'camp.module("foo.bar.baz", function(x){...})'.
     */
    private String moduleName;

    /**
     * Current module id.
     */
    private String moduleId;

    /**
     * The main method of the module that is like 'exports.main =
     * function(){...}'.
     */
    private Node main;

    /**
     * All exported value of the camp style module.
     */
    private List<String> exportedList;

    /**
     * All 'camp.using' call nodes of the camp style module.
     */
    private List<Node> usingCallList = Lists.newArrayList();

    /**
     * All exported value set of the camp style module.
     */
    private Set<Node> exportsSet = Sets.newLinkedHashSet();

    /**
     * All aliased types map of the camp style module.
     */
    private Map<String, String> aliasMap = Maps.newHashMap();

    /**
     * All rename information of the module local variable of the camp style
     * module.
     */
    private Map<String, String> varRenameMap = Maps.newHashMap();

    /**
     * All module local variable declared nodes.
     */
    private Set<Node> renameVarBaseDeclaration = Sets.newHashSet();

    /**
     * All module local variable used nodes.
     */
    private List<VarRenamePair> renameTargetList = Lists.newArrayList();

    /**
     * All exported type list of the camp style module.
     */
    private List<JSDocMutator> exportedTypeList = Lists.newArrayList();

    /**
     * All aliased type list of the camp style module.
     */
    private List<JSDocMutator> aliasTypeList = Lists.newArrayList();

    /**
     * All module local variable nodes.
     */
    private List<Node> aliasVarList = Lists.newArrayList();

    /**
     * All module local type list.
     */
    private List<JSDocMutator> localTypeList = Lists.newArrayList();

    /**
     * All type information that are liked by the type name.
     */
    private Map<String, TypeInfo> typeMap = Maps.newHashMap();

    /**
     * All module local alias information map.
     */
    private List<LocalAliasInfo> localAliasInfoList = Lists.newArrayList();

    /**
     * All forbidden alias set.
     */
    private Set<String> forbiddenAliasSet = Sets.newHashSet();


    /**
     * Constructor.
     * 
     * @param moduleName
     *          The module name.
     * @param moduleId
     *          The module id.
     * @param moduleCallNode
     *          The module declaration node.
     * @param nra
     *          The namespace referenced argument.
     */
    private ModuleInfo(String moduleName, String moduleId, Node moduleCallNode, Node nra) {
      this.moduleCallNode = moduleCallNode;
      this.moduleName = moduleName;
      this.moduleId = moduleId + "_" + id;
      this.nra = nra;
      id++;
    }


    public List<String> getExportedList() {
      return exportedList;
    }


    public void setExportedList(List<String> exportedList) {
      this.exportedList = exportedList;
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


    public void putAliasName(String name, String qualifiedName) {
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


    public void addAliasType(JSDocMutator mutator) {
      this.aliasTypeList.add(mutator);
    }


    public List<JSDocMutator> getAliasTypeList() {
      return this.aliasTypeList;
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


    public void addExportedType(JSDocMutator mutator) {
      this.exportedTypeList.add(mutator);
    }


    public List<JSDocMutator> getExportedTypeList() {
      return this.exportedTypeList;
    }


    public void addLocalType(JSDocMutator mutator) {
      this.localTypeList.add(mutator);
    }


    public List<JSDocMutator> getLocalTypeList() {
      return this.localTypeList;
    }


    public void addRenameVarBaseDeclaration(Node base) {
      this.renameVarBaseDeclaration.add(base);
    }


    public boolean isRenameVarBaseDeclaration(Node base) {
      return this.renameVarBaseDeclaration.contains(base);
    }


    public TypeInfo getTypeInfo(String name) {
      return this.typeMap.get(name);
    }


    public void setTypeInfo(TypeInfo typeInfo) {
      this.typeMap.put(typeInfo.getName(), typeInfo);
    }


    public void addLocalAliasInfo(LocalAliasInfo localAliasInfo) {
      this.localAliasInfoList.add(localAliasInfo);
    }


    public List<LocalAliasInfo> getLocalAliasInfoList() {
      return this.localAliasInfoList;
    }


    public void addForbiddenAlias(String name) {
      this.forbiddenAliasSet.add(name);
    }


    public boolean isForbiddenAlias(String name) {
      return this.forbiddenAliasSet.contains(name);
    }


    public Set<String> getForbiddenAliasSet() {
      return this.forbiddenAliasSet;
    }
  }
}
