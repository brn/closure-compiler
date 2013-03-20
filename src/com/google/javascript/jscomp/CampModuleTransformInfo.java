package com.google.javascript.jscomp;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import com.google.common.collect.Lists;
import com.google.javascript.jscomp.Scope;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.JSDocInfo;

public final class CampModuleTransformInfo {
	private static int id = 0;
	private List<Node> usingCallList = Lists.newArrayList();
	private List<Node> exportsList = Lists.newArrayList();
	private List<Node> moduleCallList = Lists.newArrayList();
	private List<CampModuleTransformInfo.JSDocAndScopeInfo> jsdocInfoList = Lists.newArrayList();
	private Map<String, Node> aliasMap = new HashMap<String, Node>();
	private int moduleId = id++;
	private Map<String, String> varRenameMap = new HashMap<String, String>();
	
	public List<Node> getUsingCallList() {
		return usingCallList;
	}

	public List<Node> getExportsList() {
		return exportsList;
	}

	public List<Node> getModuleCallList() {
		return moduleCallList;
	}

	public List<CampModuleTransformInfo.JSDocAndScopeInfo> getJsDocInfoList() {
		return jsdocInfoList;
	}
	
	public Map<String, Node> getAliasMap() {
		return this.aliasMap;
	}
	
	public void putAliasMap(String name, Node node) {
		this.aliasMap.put(name, node);
	}
	
	public int getModuleId() {
		return this.moduleId;
	}
	
	public Map<String,String> getRenameMap() {
		return varRenameMap;
	}
	
	public final class JSDocAndScopeInfo {
		private Scope scope;
		
		private int depth;
		
		private int moduleId;
		
		private JSDocInfo jsDocInfo;
		
		public JSDocAndScopeInfo(JSDocInfo jsDocInfo, Scope scope, int depth, int moduleId) {
			this.jsDocInfo = jsDocInfo;
			this.scope = scope;
			this.depth = depth;
			this.moduleId = moduleId;
		}

		public Scope getScope() {
			return scope;
		}

		public int getDepth() {
			return depth;
		}

		public JSDocInfo getJsDocInfo() {
			return jsDocInfo;
		}
		
		public int getModuleId() {
			return moduleId;
		}
	}
}
