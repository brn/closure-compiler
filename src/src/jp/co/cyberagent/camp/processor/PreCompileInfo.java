package jp.co.cyberagnet.camp.processor;

import java.util.List;
import com.google.javascript.rhino.Node;
import com.google.common.collect.Lists;
import com.google.javascript.rhino.JSDocInfo;

public final class PreCompileInfo {
	private List<Node> useCalls;
	private List<Node> exportsList;
	private List<Node> moduleCalls;
	private List<JSDocInfo> jsdocs;
	
	public PreCompileInfo () {
		useCalls = Lists.newArrayList();
		exportsList = Lists.newArrayList();
		moduleCalls = Lists.newArrayList();
		jsdocs = Lists.newArrayList();
	}

	public List<Node> getUseCalls () {
		return useCalls;
	}

	public List<Node> getExportsList () {
		return exportsList;
	}

	public List<Node> getModuleCalls () {
		return moduleCalls;
	}

	public List<JSDocInfo> getJSDocs() {
		return jsdocs;
	}
};
