package com.hrh.servicearrange.vo;

public class Task4MQ {

	private String id;
    private String instId;
    private String state;
    private String type;
	public String getId() {
		return id;
	}
	public void setId(String id) {
		this.id = id;
	}
	public String getInstId() {
		return instId;
	}
	public void setInstId(String instId) {
		this.instId = instId;
	}
	public String getState() {
		return state;
	}
	public void setState(String state) {
		this.state = state;
	}
	public String getType() {
		return type;
	}
	public void setType(String type) {
		this.type = type;
	}
	
   public Task4MQ() {
   }
   
	public Task4MQ(String id, String instId, String state, String type) {
		super();
		this.id = id;
		this.instId = instId;
		this.state = state;
		this.type = type;
	}
	public Task4MQ(String id, String type) {
		super();
		this.id = id;
		this.type = type;
	}
}
