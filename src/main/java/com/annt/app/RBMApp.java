package com.annt.app;

import java.util.Iterator;
import java.util.Random;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.Function;
import org.apache.spark.api.java.function.Function2;
import org.apache.spark.broadcast.Broadcast;
import org.jblas.DoubleMatrix;
import org.jblas.MatrixFunctions;

import scala.Tuple2;

import com.alibaba.fastjson.JSONObject;
import com.annt.network.RBMNetwork;
import com.annt.obj.RBMUpdateParameters;
import com.annt.obj.UnLabeledDoubleSample;
import com.annt.trainning.CDKBackPropagation;
import com.annt.utils.CommonUtils;
import com.smiims.obj.GeoTSShortVector;

public class RBMApp extends SparkApp {

	/**
	 * 
	 */
	private static final long serialVersionUID = -1392619231265081058L;

	public RBMApp() {
	}

	// 读取RBM配置
	public void loadConf(String path) {
		JSONObject json_conf = JSONObject.parseObject(CommonUtils
				.readJSONText(path));
		// 读取各种RBM的参数
		vDimension = json_conf.getIntValue("vDimension");
		hDimension = json_conf.getIntValue("hDimension");
		rbmSavePath = json_conf.getString("rbmSavePath");
		rbmBestSavePath = json_conf.getString("rbmBestSavePath");
		time = json_conf.getIntValue("time");
		max_error = json_conf.getDoubleValue("max_error");
		learning_rate = json_conf.getDoubleValue("learning_rate");
		lamda = json_conf.getDoubleValue("lamda");
		divRatio = json_conf.getIntValue("divRatio");
		groupNum = json_conf.getIntValue("groupNum");
		rbm = new RBMNetwork(vDimension, hDimension, divRatio);
	}

	public static JavaRDD<UnLabeledDoubleSample> prepareDataset(
			JavaSparkContext jsc) {
		JavaRDD<GeoTSShortVector> vectorsRDD = jsc
				.objectFile("hdfs://192.168.1.140:9000/user/terry/ts_data/MODIS/2000049_2010353/MOD13/h23v04/b1");
		// 数据采样
		vectorsRDD = vectorsRDD.sample(true, 0.00021);
		JavaRDD<UnLabeledDoubleSample> doubleVectorsRDD = vectorsRDD
				.map(new Function<GeoTSShortVector, UnLabeledDoubleSample>() {

					/**
					 * 将源数据转化为Double向量RDD
					 */
					private static final long serialVersionUID = -566735331026837033L;

					public UnLabeledDoubleSample call(GeoTSShortVector v)
							throws Exception {
						double data[] = new double[v.data.length];
						for (int i = 0; i < v.data.length; i++) {
							data[i] = v.data[i];
						}
						UnLabeledDoubleSample result = new UnLabeledDoubleSample(
								new DoubleMatrix(data));
						return result;
					}
				});
		// 数据归一化
		doubleVectorsRDD = doubleVectorsRDD
				.map(new Function<UnLabeledDoubleSample, UnLabeledDoubleSample>() {

					private static final long serialVersionUID = -4340324733631709708L;

					public UnLabeledDoubleSample call(UnLabeledDoubleSample v)
							throws Exception {
						v.data.addi(2000).divi(12000);
						return v;
					}
				});
		return doubleVectorsRDD;
	}

	public static void main(String args[]) {
		SparkConf conf = CommonUtils.readSparkConf("rbm_spark_conf.json");
		JavaSparkContext jsc = new JavaSparkContext(conf);
		RBMApp app = new RBMApp();
		app.loadConf("rbm_parameters.json");
		// 数据集准备
		JavaRDD<UnLabeledDoubleSample> dataset = prepareDataset(jsc);
		long datasetSize = dataset.count();
		// 数据集分组
		JavaPairRDD<Integer, Iterable<UnLabeledDoubleSample>> groupedDataset = app
				.groupedDataset(dataset);
		groupedDataset = groupedDataset.cache();
		// 定义RBM网络
		RBMNetwork localRBMNetwork = new RBMNetwork(app.vDimension,
				app.hDimension, app.divRatio);
		final Broadcast<RBMNetwork> bRBMNetwork = jsc
				.broadcast(localRBMNetwork);
		double error = 0.0, min_error = Double.MAX_VALUE;
		// 一次训练
		for (int i = 0; i < app.time; i++) {
			RBMUpdateParameters updateParameters = app.train(bRBMNetwork,
					groupedDataset);
			// 更新参数求平均
			updateParameters.div(datasetSize);
			updateParameters.addLamdaWeight(0.05, localRBMNetwork.weight);
			localRBMNetwork.updateRBM(updateParameters.wu, updateParameters.vu,
					updateParameters.hu, app.learning_rate);
			DoubleMatrix errorVector = app
					.getError(bRBMNetwork, groupedDataset);
			error = errorVector.divi(datasetSize).norm2();
			if (min_error > error) {
				min_error = error;
				RBMNetwork.saveNetwork(app.rbmBestSavePath, localRBMNetwork);
			}
			System.out.println("第" + (i + 1) + "次迭代：" + error);
		}
		RBMNetwork.saveNetwork(app.rbmSavePath, localRBMNetwork);
		jsc.stop();
	}

	// 将数据集分组
	public JavaPairRDD<Integer, Iterable<UnLabeledDoubleSample>> groupedDataset(
			JavaRDD<UnLabeledDoubleSample> dataset) {
		return dataset.groupBy(new Function<UnLabeledDoubleSample, Integer>() {

			private static final long serialVersionUID = 487890844179550294L;

			public Integer call(UnLabeledDoubleSample v) throws Exception {
				int id = Math.abs(v.hashCode() + seed.nextInt()) % groupNum;
				return id;
			}
		});
	}

	// 分组训练
	public RBMUpdateParameters train(final Broadcast<RBMNetwork> rbmNetwork,
			JavaPairRDD<Integer, Iterable<UnLabeledDoubleSample>> groupedDataset) {
		// map 分组训练数据集，返回需要更新的参数
		JavaRDD<RBMUpdateParameters> updateParameters = groupedDataset
				.map(new Function<Tuple2<Integer, Iterable<UnLabeledDoubleSample>>, RBMUpdateParameters>() {
					private static final long serialVersionUID = -6651852006259878407L;

					RBMNetwork rbm = rbmNetwork.getValue();

					public RBMUpdateParameters call(
							Tuple2<Integer, Iterable<UnLabeledDoubleSample>> v)
							throws Exception {
						CDKBackPropagation cdk = new CDKBackPropagation(rbm);
						Iterator<UnLabeledDoubleSample> iterator = v._2
								.iterator();
						RBMUpdateParameters updateParameters = new RBMUpdateParameters(
								rbm);
						while (iterator.hasNext()) {
							UnLabeledDoubleSample sample = iterator.next();
							cdk.updateMatrixAndBias(sample.data);
							updateParameters.addAll(cdk.vbu, cdk.hbu, cdk.wu);
						}
						return updateParameters;
					}
				});
		// reduce 将更新的参数sum求和
		return updateParameters
				.reduce(new Function2<RBMUpdateParameters, RBMUpdateParameters, RBMUpdateParameters>() {
					private static final long serialVersionUID = 920253502963574726L;

					public RBMUpdateParameters call(RBMUpdateParameters v1,
							RBMUpdateParameters v2) throws Exception {
						v1.wu.addi(v2.wu);
						v1.hu.addi(v2.hu);
						v1.vu.addi(v2.vu);
						return v1;
					}
				});
	}

	// 获得RBM还原误差
	public DoubleMatrix getError(final Broadcast<RBMNetwork> rbmNetwork,
			JavaPairRDD<Integer, Iterable<UnLabeledDoubleSample>> groupedDataset) {
		JavaRDD<DoubleMatrix> errors = groupedDataset
				.map(new Function<Tuple2<Integer, Iterable<UnLabeledDoubleSample>>, DoubleMatrix>() {

					private static final long serialVersionUID = -8169574227613805716L;

					RBMNetwork rbm = rbmNetwork.getValue();

					public DoubleMatrix call(
							Tuple2<Integer, Iterable<UnLabeledDoubleSample>> v)
							throws Exception {
						DoubleMatrix sum_error = DoubleMatrix.zeros(rbm.vn);
						Iterator<UnLabeledDoubleSample> iterator = v._2
								.iterator();
						while (iterator.hasNext()) {
							UnLabeledDoubleSample sample = iterator.next();
							DoubleMatrix restoreSign = rbm.getVOutput(rbm
									.getHOutput(sample.data));
							DoubleMatrix error = MatrixFunctions
									.abs(restoreSign.sub(sample.data));
							sum_error.addi(error);
						}
						return sum_error;
					}
				});
		return errors
				.reduce(new Function2<DoubleMatrix, DoubleMatrix, DoubleMatrix>() {

					private static final long serialVersionUID = -3901218453435043374L;

					public DoubleMatrix call(DoubleMatrix v1, DoubleMatrix v2)
							throws Exception {
						return v1.add(v2);
					}
				});
	}

	// {
	// // 设置日志等级
	// Logger.getLogger("org").setLevel(Level.OFF);
	// Logger.getLogger("akka").setLevel(Level.OFF);
	// }

	// 随机数种子
	public Random seed = new Random();

	// 输入维度
	public int vDimension;

	// 隐藏层维度
	public int hDimension;

	// 模型存储路径
	public String rbmSavePath;

	// 最优模型存储路径
	public String rbmBestSavePath;

	// 迭代次数
	public int time;

	// 允许最大误差
	public double max_error;

	// 学习率
	public double learning_rate;

	// 权值衰减参数权值
	public double lamda;

	// 权值放缩参数
	public int divRatio;

	// 数据集分组数
	public int groupNum;

	public RBMNetwork rbm;
}
