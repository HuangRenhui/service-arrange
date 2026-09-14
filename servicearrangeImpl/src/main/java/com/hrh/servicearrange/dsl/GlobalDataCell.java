package com.hrh.servicearrange.dsl;


import com.hrh.servicearrange.parser.annotation.CellType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 全局参数 
 *
 */
public class GlobalDataCell extends Cell{
	
	private String cellType = CellType.GLOBAL_DATA;
	private Data data;
	
	public class Data {
		private List<Map<String, Object>> staticParams = new ArrayList<>(); //静态全局参数(常量)
		private List<Map<String, Object>> dynamicParams = new ArrayList<>(); //动态全局参数(变量)

		public List<Map<String, Object>> getStaticParams() {
			return staticParams;
		}

		public void setStaticParams(List<Map<String, Object>> staticParams) {
			this.staticParams = staticParams;
		}

		public List<Map<String, Object>> getDynamicParams() {
			return dynamicParams;
		}

		public void setDynamicParams(List<Map<String, Object>> dynamicParams) {
			this.dynamicParams = dynamicParams;
		}
	}

	public String getCellType() {
		return cellType;
	}

	public void setCellType(String cellType) {
		this.cellType = cellType;
	}

	public Data getData() {
		return data;
	}

	public void setData(Data data) {
		this.data = data;
	}
}
