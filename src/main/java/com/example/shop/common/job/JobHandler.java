package com.example.shop.common.job;

public interface JobHandler {

	String type();

	void handle(String payload) throws Exception;
}
