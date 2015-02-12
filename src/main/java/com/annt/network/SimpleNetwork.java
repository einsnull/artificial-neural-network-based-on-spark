package com.annt.network;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.LinkedList;

import org.jblas.DoubleMatrix;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONException;
import com.alibaba.fastjson.JSONObject;
import com.annt.interf.ActiveFunction;
import com.annt.layer.BasicLayer;

public class SimpleNetwork extends BasicNetwork {
	/**
	 * 实现对一个简单的神经网络结构的定义
	 */
	private static final long serialVersionUID = 8515281334911432901L;
	// 细胞层
	public LinkedList<BasicLayer> layers;
	// 权值矩阵
	public LinkedList<DoubleMatrix> weights;
	// 偏置
	public LinkedList<DoubleMatrix> biass;

	// 构造函数
	public SimpleNetwork() {
		layers = new LinkedList<BasicLayer>();
		weights = new LinkedList<DoubleMatrix>();
		biass = new LinkedList<DoubleMatrix>();
	}

	// 序列化存储神经网络
	public static void saveNetwork(String path, SimpleNetwork network) {
		try {
			ObjectOutputStream out = new ObjectOutputStream(
					new FileOutputStream(path));
			out.writeObject(network);
			out.close();
		} catch (FileNotFoundException e) {
			// log
			e.printStackTrace();
		} catch (IOException e) {
			// log
			e.printStackTrace();
		}
	}

	// 载入神经网络
	public static SimpleNetwork loadNetwork(String path) {
		try {
			ObjectInputStream in = new ObjectInputStream(new FileInputStream(
					path));
			SimpleNetwork network = (SimpleNetwork) in.readObject();
			in.close();
			return network;
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		} catch (ClassNotFoundException e) {
			e.printStackTrace();
		}
		return null;
	}

	public SimpleNetwork(JSONObject json) {
		JSONArray cell_array = json.getJSONArray("cell");
		JSONArray bias_array = json.getJSONArray("bias");
		JSONArray active_array = json.getJSONArray("active");
		double weight_ratio = json.getDouble("weight_ratio");
		//
		layers = new LinkedList<BasicLayer>();
		weights = new LinkedList<DoubleMatrix>();
		biass = new LinkedList<DoubleMatrix>();
		try {
			for (int i = 0; i < cell_array.size(); i++) {
				// 反射获得激活函数
				Class<?> c = Class.forName(active_array.getString(i));
				ActiveFunction func = (ActiveFunction) c.newInstance();
				addLayer(new BasicLayer(cell_array.getIntValue(i),
						bias_array.getBooleanValue(i), func));
			}
		} catch (JSONException e) {
			System.err.println("JSON解析错误...");
		} catch (ClassNotFoundException e) {
			System.err.println("未定义激活函数");
		} catch (InstantiationException e) {
			System.err.println("激活函数无构造函数");
		} catch (IllegalAccessException e) {
			System.err.println("激活函数权限出错");
		}
		initNetwork(weight_ratio);
	}

	public LinkedList<DoubleMatrix> getOutputs(DoubleMatrix input) {
		// 自上而下返回输出值
		LinkedList<DoubleMatrix> outputs = new LinkedList<DoubleMatrix>();
		// 添加第一层,输出第一层为input
		outputs.add(input);
		DoubleMatrix w;
		BasicLayer l;
		ActiveFunction f;
		DoubleMatrix x = input;
		DoubleMatrix b;
		for (int i = 0; i < weights.size(); i++) {
			w = weights.get(i);
			b = biass.get(i);
			l = layers.get(i + 1);
			f = l.activeFunc;
			x = f.output(w, x, b);
			outputs.add(x);
		}
		return outputs;
	}

	// 添加神经层
	public void addLayer(BasicLayer l) {
		layers.add(l);
	}

	// divRatio数值为1的神经网络权值初始化
	public void initNetwork() {
		initNetwork(1);
	}

	// 输入单/多样本获得输出
	public DoubleMatrix getOutput(DoubleMatrix input) {
		DoubleMatrix w;
		BasicLayer l;
		ActiveFunction f;
		DoubleMatrix x = input;
		DoubleMatrix b;
		for (int i = 0; i < weights.size(); i++) {
			w = weights.get(i);
			b = biass.get(i);
			l = layers.get(i + 1);
			f = l.activeFunc;
			x = f.output(w, x, b);
		}
		return x;
	}

	// 给定权值数组、偏置数组以及学习率更新矩阵
	public void updateNet(LinkedList<DoubleMatrix> w,
			LinkedList<DoubleMatrix> b, double learning_rate) {
		for (int i = 0; i < weights.size(); i++) {
			DoubleMatrix uw = w.get(i);
			DoubleMatrix ub = b.get(i);
			weights.get(i).subi(uw.mul(learning_rate));
			if (ub != null) {
				biass.get(i).subi(ub.mul(learning_rate));
			}
		}
	}

	// 初始化神经网络
	public void initNetwork(double divRatio) {
		// 少于两层无法创建神经网络结构，divRatio等于0无法除
		if (layers.size() < 2 || divRatio == 0) {
			// log
			return;
		}
		// 上一层网络以及下一层网络
		BasicLayer lowwerLayer, upperLayer;
		// 每个权值矩阵输入输出维度
		int input_d, output_d;
		for (int i = 0; i < layers.size() - 1; i++) {
			lowwerLayer = layers.get(i);
			upperLayer = layers.get(i + 1);
			// 得到上下层神经元数目
			input_d = lowwerLayer.neural_num;
			output_d = upperLayer.neural_num;
			// 两层神经网络确定一个权值矩阵
			DoubleMatrix w = DoubleMatrix.rand(input_d, output_d).div(divRatio);
			weights.add(w);
			// 存在偏置矩阵
			if (upperLayer.bias) {
				DoubleMatrix b = DoubleMatrix.rand(output_d).div(divRatio);
				biass.add(b);
			} else {
				biass.add(null);
			}
		}
	}
}
