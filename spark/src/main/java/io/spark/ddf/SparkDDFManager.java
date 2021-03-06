package io.spark.ddf;


import com.google.gson.Gson;
import io.ddf.DDF;
import io.ddf.DDFManager;
import io.ddf.exception.DDFException;
import io.spark.ddf.util.SparkUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.spark.SparkContext;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.sql.hive.HiveContext;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

//import shark.SharkEnv;
//import shark.api.JavaSharkContext;

/**
 * An Apache-Spark-based implementation of DDFManager
 */
public class SparkDDFManager extends DDFManager {

  @Override
  public String getEngine() {
    return "spark";
  }


  private static final String DEFAULT_SPARK_APPNAME = "DDFClient";
  private static final String DEFAULT_SPARK_MASTER = "local[4]";



  public SparkDDFManager(SparkContext sparkContext) throws DDFException {
    this.initialize(sparkContext, null);
  }

  /**
   * Use system environment variables to configure the SparkContext creation.
   *
   * @throws DDFException
   */
  public SparkDDFManager() throws DDFException {
    this.initialize(null, new HashMap<String, String>());
  }

  public SparkDDFManager(Map<String, String> params) throws DDFException {
    this.initialize(null, params);
  }

  private void initialize(SparkContext sparkContext, Map<String, String> params) throws DDFException {
    this.setSparkContext(sparkContext == null ? this.createSparkContext(params) : sparkContext);
    this.mHiveContext = new HiveContext(this.mSparkContext);
    String compression = System.getProperty("spark.sql.inMemoryColumnarStorage.compressed", "true");
    String batchSize = System.getProperty("spark.sql.inMemoryColumnarStorage.batchSize", "1000");
    mLog.info(">>>> spark.sql.inMemoryColumnarStorage.compressed= " + compression);
    mLog.info(">>>> spark.sql.inMemoryColumnarStorage.batchSize= " + batchSize);
    this.mHiveContext.setConf("spark.sql.inMemoryColumnarStorage.compressed", compression);
    this.mHiveContext.setConf("spark.sql.inMemoryColumnarStorage.batchSize", batchSize);
  }



  public String getDDFEngine() {
    return "spark";
  }


  private SparkContext mSparkContext;

  private JavaSparkContext mJavaSparkContext;

  public SparkContext getSparkContext() {
    return mSparkContext;
  }

  private void setSparkContext(SparkContext sparkContext) {
    this.mSparkContext = sparkContext;
  }

  private HiveContext mHiveContext;

  private static final String[][] SPARK_ENV_VARS = new String[][] {
    // @formatter:off
    { "SPARK_APPNAME", "spark.appname" },
    { "SPARK_MASTER", "spark.master" },
    { "SPARK_HOME", "spark.home" },
    { "SPARK_SERIALIZER", "spark.kryo.registrator" },
    { "HIVE_HOME", "hive.home" },
    { "HADOOP_HOME", "hadoop.home" },
    { "DDFSPARK_JAR", "ddfspark.jar" }
    // @formatter:on
  };

  public HiveContext getHiveContext() {
    return mHiveContext;
  }
  //  private SparkUtils.createSharkContext mSharkContext;
  //
  //
  //  public SharkContext getSharkContext() {
  //    return mSharkContext;
  //  }

  //  private JavaSharkContext mJavaSharkContext;
  //
  //
  //  public JavaSharkContext getJavaSharkContext() {
  //    return mJavaSharkContext;
  //  }

  //  public void setJavaSharkContext(JavaSharkContext javaSharkContext) {
  //    this.mJavaSharkContext = javaSharkContext;
  //  }

  /**
   * Also calls setSparkContext() to the same sharkContext
   *
   * @param sharkContext
   */
  //  private void setSharkContext(SharkContext sharkContext) {
  //    this.mSharkContext = sharkContext;
  //    this.setSparkContext(sharkContext);
  //  }


  private Map<String, String> mSparkContextParams;


  public Map<String, String> getSparkContextParams() {
    return mSparkContextParams;
  }

  private void setSparkContextParams(Map<String, String> mSparkContextParams) {
    this.mSparkContextParams = mSparkContextParams;
  }
   /* merge priority is as follows: (1) already set in params, (2) in system properties (e.g., -Dspark.home=xxx), (3) in
   * environment variables (e.g., export SPARK_HOME=xxx)
   *
   * @param params
   * @return
   */
  private Map<String, String> mergeSparkParamsFromSettings(Map<String, String> params) {
    if (params == null) params = new HashMap<String, String>();

    Map<String, String> env = System.getenv();

    for (String[] varPair : SPARK_ENV_VARS) {
      if (params.containsKey(varPair[0])) continue; // already set in params

      // Read setting either from System Properties, or environment variable.
      // Env variable has lower priority if both are set.
      String value = System.getProperty(varPair[1], env.get(varPair[0]));
      if (value != null && value.length() > 0) params.put(varPair[0], value);
    }

    // Some well-known defaults
    if (!params.containsKey("SPARK_MASTER")) params.put("SPARK_MASTER", DEFAULT_SPARK_MASTER);
    if (!params.containsKey("SPARK_APPNAME")) params.put("SPARK_APPNAME", DEFAULT_SPARK_APPNAME);
    params.put("SPARK_SERIALIZER", "io.spark.content.KryoRegistrator");
    Gson gson = new Gson();

    mLog.info(String.format(">>>>>>> params = %s", gson.toJson(params)));

    return params;
  }


  /**
   * Side effect: also sets SharkContext and SparkContextParams in case the client wants to examine or use those.
   *
   * @param params
   * @return
   * @throws DDFException
   */
  private SparkContext createSparkContext(Map<String, String> params) throws DDFException {
    this.setSparkContextParams(this.mergeSparkParamsFromSettings(params));
    String ddfSparkJar = params.get("DDFSPARK_JAR");
    String[] jobJars = ddfSparkJar != null ? ddfSparkJar.split(",") : new String[] { };
    mLog.info(">>>>> ddfSparkJar = " + ddfSparkJar);

    for (String key : params.keySet()) {
      mLog.info(">>>> key = " + key + ", value = " + params.get(key));
    }

    SparkContext context = SparkUtils.createSparkContext(params.get("SPARK_MASTER"), params.get("SPARK_APPNAME"),
        params.get("SPARK_HOME"), jobJars, params);
    this.mSparkContext = context;
    this.mJavaSparkContext = new JavaSparkContext(context);
    return this.getSparkContext();
  }

  public DDF loadTable(String fileURL, String fieldSeparator) throws DDFException {
    JavaRDD<String> fileRDD = mJavaSparkContext.textFile(fileURL);
    String[] metaInfos = getMetaInfo(fileRDD, fieldSeparator);
    SecureRandom rand = new SecureRandom();
    String tableName = "tbl" + String.valueOf(Math.abs(rand.nextLong()));
    String cmd = "CREATE TABLE " + tableName + "(" + StringUtils.join(metaInfos, ", ")
        + ") ROW FORMAT DELIMITED FIELDS TERMINATED BY '" + fieldSeparator + "'";
    sql2txt(cmd);
    sql2txt("LOAD DATA LOCAL INPATH '" + fileURL + "' " +
        "INTO TABLE " + tableName);
    return sql2ddf("SELECT * FROM " + tableName);
  }

  /**
   * Given a String[] vector of data values along one column, try to infer what the data type should be.
   * <p/>
   * TODO: precompile regex
   *
   * @param vector
   * @return string representing name of the type "integer", "double", "character", or "logical" The algorithm will
   * first scan the vector to detect whether the vector contains only digits, ',' and '.', <br>
   * if true, then it will detect whether the vector contains '.', <br>
   * &nbsp; &nbsp; if true then the vector is double else it is integer <br>
   * if false, then it will detect whether the vector contains only 'T' and 'F' <br>
   * &nbsp; &nbsp; if true then the vector is logical, otherwise it is characters
   */
  public static String determineType(String[] vector, Boolean doPreferDouble) {
    boolean isNumber = true;
    boolean isInteger = true;
    boolean isLogical = true;
    boolean allNA = true;

    for (String s : vector) {
      if (s == null || s.startsWith("NA") || s.startsWith("Na") || s.matches("^\\s*$")) {
        // Ignore, don't set the type based on this
        continue;
      }

      allNA = false;

      if (isNumber) {
        // match numbers: 123,456.123 123 123,456 456.123 .123
        if (!s.matches("(^|^-)((\\d+(,\\d+)*)|(\\d*))\\.?\\d+$")) {
          isNumber = false;
        }
        // match double
        else if (isInteger && s.matches("(^|^-)\\d*\\.{1}\\d+$")) {
          isInteger = false;
        }
      }

      // NOTE: cannot use "else" because isNumber changed in the previous
      // if block
      if (isLogical && !s.toLowerCase().matches("^t|f|true|false$")) {
        isLogical = false;
      }
    }

    // String result = "Unknown";
    String result = "string";

    if (!allNA) {
      if (isNumber) {
        if (!isInteger || doPreferDouble) {
          result = "double";
        } else {
          result = "int";
        }
      } else {
        if (isLogical) {
          result = "boolean";
        } else {
          result = "string";
        }
      }
    }
    return result;
  }

  /**
   * TODO: check more than a few lines in case some lines have NA
   *
   * @param fileRDD
   * @return
   */
  public String[] getMetaInfo(JavaRDD<String> fileRDD, String fieldSeparator) {
    String[] headers = null;
    int sampleSize = 5;

    // sanity check
    if (sampleSize < 1) {
      mLog.info("DATATYPE_SAMPLE_SIZE must be bigger than 1");
      return null;
    }

    List<String> sampleStr = fileRDD.take(sampleSize);
    sampleSize = sampleStr.size(); // actual sample size
    mLog.info("Sample size: " + sampleSize);

    // create sample list for getting data type
    String[] firstSplit = sampleStr.get(0).split(fieldSeparator);

    // get header
    boolean hasHeader = false;
    if (hasHeader) {
      headers = firstSplit;
    } else {
      headers = new String[firstSplit.length];
      int size = headers.length;
      for (int i = 0; i < size; ) {
        headers[i] = "V" + (++i);
      }
    }

    String[][] samples = hasHeader ? (new String[firstSplit.length][sampleSize - 1])
        : (new String[firstSplit.length][sampleSize]);

    String[] metaInfoArray = new String[firstSplit.length];
    int start = hasHeader ? 1 : 0;
    for (int j = start; j < sampleSize; j++) {
      firstSplit = sampleStr.get(j).split(fieldSeparator);
      for (int i = 0; i < firstSplit.length; i++) {
        samples[i][j - start] = firstSplit[i];
      }
    }

    boolean doPreferDouble = true;
    for (int i = 0; i < samples.length; i++) {
      String[] vector = samples[i];
      metaInfoArray[i] = headers[i] + " " + determineType(vector, doPreferDouble);
    }

    return metaInfoArray;
  }
}  

