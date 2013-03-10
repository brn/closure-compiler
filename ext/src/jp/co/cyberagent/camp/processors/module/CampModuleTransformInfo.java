package jp.co.cyberagnet.camp.processors.module;

import java.util.List;
import com.google.common.collect.Lists;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.JSDocInfo;

public final class CampModuleTransformInfo {
	private List<Node> usingCallList = Lists.newArrayList();
	private List<Node> exportsList = Lists.newArrayList();
	private List<Node> moduleCallList = Lists.newArrayList();
	private List<JSDocInfo> jsdocList = Lists.newArrayList();
	
	public List<Node> getUsingCallList() {
		return usingCallList;
	}

	public List<Node> getExportsList() {
		return exportsList;
	}

	public List<Node> getModuleCallList() {
		return moduleCallList;
	}

	public List<JSDocInfo> getJsDocList() {
		return jsdocList;
	}
}
