package com.nfwork.dbfound.core;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import javax.sql.DataSource;

import org.apache.commons.beanutils.BeanUtils;
import org.apache.commons.beanutils.MethodUtils;
import org.dom4j.Document;
import org.dom4j.Element;
import org.dom4j.io.SAXReader;

import com.nfwork.dbfound.db.ConnectionProvide;
import com.nfwork.dbfound.db.DataSourceConnectionProvide;
import com.nfwork.dbfound.db.JdbcConnectionProvide;
import com.nfwork.dbfound.exception.DBFoundRuntimeException;
import com.nfwork.dbfound.model.ModelReader;
import com.nfwork.dbfound.util.DataUtil;
import com.nfwork.dbfound.util.LogUtil;
import com.nfwork.dbfound.web.ActionEngine;
import com.nfwork.dbfound.web.DispatcherFilter;
import com.nfwork.dbfound.web.InterceptorEngine;
import com.nfwork.dbfound.web.WebWriter;
import com.nfwork.dbfound.web.file.FileUploadUtil;
import com.nfwork.dbfound.web.file.FileUtil;
import com.nfwork.dbfound.web.i18n.MultiLangUtil;

public class DBFoundConfig {

	private static String listenerClass;
	private static SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss,SSS");
	private static List<DataSourceConnectionProvide> dsp = new ArrayList<DataSourceConnectionProvide>();

	public static final String CLASSPATH = "${@classpath}";
	public static final String PROJECT_ROOT = "${@projectRoot}";

	private static boolean inited = false;
	private static String configFilePath;
	private static String classpath;
	private static String projectRoot;
	private static boolean queryLimit = true;
	private static int queryLimitSize = 5000;
	private static int reportQueryLimitSize = 50000;

	public static void destory() {
		for (DataSourceConnectionProvide provide : dsp) {
			DataSource dataSource = provide.getDataSource();
			if (dataSource != null) {
				try {
					System.out.println(dateFormat.format(new Date()) + " dbfound close dataSource :" + provide.getProvideName());
					MethodUtils.invokeMethod(dataSource, "close", new Object[] {});
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		}
	}

	public static void init() {
		if (inited) {
			return;
		}
		init(getConfigFilePath());
	}

	public synchronized static void init(String confFile) {
		if (confFile == null || "".equals(confFile)) {
			confFile = CLASSPATH + "/dbfound-conf.xml";
		}
		if (inited) {
			return;
		} else {
			inited = true;
			if (configFilePath == null) {
				setConfigFilePath(confFile);
			}
		}
		try {
			System.out.println("**************************************************************************");
			System.out.println(dateFormat.format(new Date()) + " NFWork dbfound service init begin");
			SAXReader reader = new SAXReader();
			File file = new File(getRealPath(confFile));
			Document doc = null;
			if (file.exists()) {
				System.out.println(dateFormat.format(new Date()) + " user config file: " + PathFormat.format(file.getAbsolutePath()));
				doc = reader.read(file);
			} else if (confFile.startsWith(CLASSPATH)) {
				ClassLoader loader = Thread.currentThread().getContextClassLoader();
				InputStream inputStream = null;
				try {
					URL url = loader.getResource(confFile.substring(CLASSPATH.length() + 1));
					if (url != null) {
						if (url.getFile() != null) {
							file = new File(url.getFile());
						}
						if (file.exists()) {
							System.out.println(dateFormat.format(new Date()) + " user config file: " + PathFormat.format(file.getAbsolutePath()));
							doc = reader.read(file);
						} else {
							System.out.println(dateFormat.format(new Date()) + " user config file: " + PathFormat.format(url.getFile()));
							inputStream = url.openStream();
							doc = reader.read(inputStream);
						}
					}
				} finally {
					if (inputStream != null) {
						inputStream.close();
					}
				}
			}

			if (doc != null) {
				Element root = doc.getRootElement();

				// system参数初始化
				Element system = root.element("system");
				if (system != null) {
					initSystem(system);
				}

				// 数据库初始化
				Element database = root.element("database");
				if (database != null) {
					initDB(database);
				}

				// web参数初始化
				Element web = root.element("web");
				if (web != null) {
					initWeb(web);
				}

				if (listenerClass != null) {
					try {
						StartListener listener = (StartListener) Class.forName(listenerClass).newInstance();
						listener.execute();
						System.out.println(dateFormat.format(new Date()) + " invoke listenerClass success");
					} catch (Exception e) {
						LogUtil.error("invoke listenerClass faild", e);
					}
				}
			} else {
				System.out.println(dateFormat.format(new Date()) + " config file init skiped, because file not found. filePath:"
						+ file.getAbsolutePath());
			}
			System.out.println(dateFormat.format(new Date()) + " NFWork dbfound service init success");
			System.out.println("**************************************************************************");
		} catch (Exception e) {
			LogUtil.error("dbfound init faild，please check config", e);
			if (e instanceof RuntimeException) {
				throw (RuntimeException) e;
			}
		}

	}

	@SuppressWarnings("unchecked")
	private static void initDB(Element database) throws InstantiationException, IllegalAccessException, ClassNotFoundException,
			InvocationTargetException {

		List<Element> jdbcProvides = database.elements("jdbcConnectionProvide");
		for (Element element : jdbcProvides) {
			String provideName = getString(element, "provideName");
			String url = getString(element, "url");
			String driverClass = getString(element, "driverClass");
			String username = getString(element, "username");
			String password = getString(element, "password");
			String dialect = getString(element, "dialect");
			if (provideName == null || provideName.equals("")) {
				provideName = "_default";
			}
			if (dialect != null && url != null && driverClass != null && username != null && !"".equals(dialect) && !"".equals(driverClass)
					&& !"".equals(url) && !"".equals(username)) {
				ConnectionProvide provide = new JdbcConnectionProvide(provideName, url, driverClass, dialect, username, password);
				provide.regist();
				System.out.println(dateFormat.format(new Date()) + " regist jdbcConnProvide success, provideName:" + provideName);
			} else {
				throw new DBFoundRuntimeException("user jdbc type，url driverClass username dialect can not be null");
			}
		}

		List<Element> dataSourceProvides = database.elements("dataSourceConnectionProvide");
		for (Element element : dataSourceProvides) {
			String provideName = getString(element, "provideName");
			String dataSource = getString(element, "dataSource");
			String dialect = getString(element, "dialect");
			String className = getString(element, "className");
			if (provideName == null || provideName.equals("")) {
				provideName = "_default";
			}
			if (dialect != null && dataSource != null && !"".equals(dialect) && !"".equals(dataSource)) {
				ConnectionProvide provide = new DataSourceConnectionProvide(provideName, dataSource, dialect);
				provide.regist();
				System.out.println(dateFormat.format(new Date()) + " regist dataSourceConnProvide success, provideName:" + provideName);
			} else if (dialect != null && className != null && !"".equals(dialect) && !"".equals(className)) {
				DataSource ds = (DataSource) Class.forName(className).newInstance();
				List<Element> properties = element.element("properties").elements("property");
				for (Element property : properties) {
					BeanUtils.setProperty(ds, property.attributeValue("name"), property.attributeValue("value"));
				}
				DataSourceConnectionProvide provide = new DataSourceConnectionProvide(provideName, ds, dialect);
				provide.regist();
				dsp.add(provide);
				System.out.println(dateFormat.format(new Date()) + " regist dataSourceConnProvide success, provideName:" + provideName);
			} else {
				throw new DBFoundRuntimeException("user dataSource type，dataSource dialect can not null");
			}
		}
	}

	private static void initWeb(Element web) {
		StringBuffer info = new StringBuffer();
		info.append(dateFormat.format(new Date()) + " set web Param:");

		// i18n 初始化
		Element provide = web.element("i18nProvide");
		if (provide != null) {
			String className = provide.getTextTrim();
			if (!"".equals(className)) {
				MultiLangUtil.init(className);
				info.append("(i18nProvide = " + className + ")");
			}
		}

		// 编码初始化
		Element enco = web.element("encoding");
		if (enco != null) {
			String encoding = enco.getTextTrim();
			if (!"".equals(encoding)) {
				WebWriter.setEncoding(encoding);
				info.append("(encoding = " + encoding + ")");
			}
		}

		// 文件上传路径
		Element folder = web.element("uploadFolder");
		if (folder != null) {
			String path = folder.getTextTrim();
			if (!"".equals(path)) {
				path = getRealPath(path);
				FileUtil.init(path);
				info.append("(uploadFolder = " + path + ")");
			}
		}

		// 文件上传路径
		Element size = web.element("maxUploadSize");
		if (size != null) {
			String maxUploadSize = size.getTextTrim();
			if (DataUtil.isNotNull(maxUploadSize)) {
				FileUploadUtil.maxUploadSize = DataUtil.intValue(maxUploadSize);
				info.append("(maxUploadSize = " + FileUploadUtil.maxUploadSize + ")");
			}
		}

		// interceptor 初始化
		Element filter = web.element("interceptor");
		if (filter != null) {
			String className = filter.getTextTrim();
			if (!"".equals(className)) {
				InterceptorEngine.init(className);
				info.append("(accessFilter = " + className + ")");
			}
		}

		System.out.println(info);

		// 初始化dbfound mvc
		Element mvc = web.element("mvcConfigFile");
		String mvcFile = null;
		if (mvc == null || "".equals(mvc.getTextTrim())) {
			mvcFile = CLASSPATH + "/dbfound-mvc.xml";
		} else {
			mvcFile = mvc.getTextTrim();
		}
		File file = new File(getRealPath(mvcFile));
		if (!file.exists() && mvcFile.startsWith(CLASSPATH)) {
			ClassLoader loader = Thread.currentThread().getContextClassLoader();
			URL url = loader.getResource(mvcFile.substring(CLASSPATH.length() + 1));
			if (url != null) {
				if (url.getFile() != null) {
					file = new File(url.getFile());
				}
			}
		}
		if (file.exists()) {
			System.out.println(dateFormat.format(new Date()) + " init mvc success, config file: " + PathFormat.format(file.getAbsolutePath()) + ")");
			ActionEngine.init(file);
		} else {
			System.out.println(dateFormat.format(new Date()) + " init mvc cancel, because file: " + PathFormat.format(getRealPath(mvcFile))
					+ ") not found");
		}
	}

	private static void initSystem(Element system) {
		StringBuffer info = new StringBuffer();
		info.append(dateFormat.format(new Date()) + " set system Param:");

		// 设置日志开关
		Element log = system.element("openLog");
		if (log != null) {
			String openLog = log.getTextTrim();
			if ("false".equals(openLog.trim())) {
				LogUtil.setOpenLog(false);
				info.append("(openLog=false) ");
			} else if ("true".equals(openLog.trim())) {
				LogUtil.setOpenLog(true);
				info.append("(openLog=true) ");
			}
		}

		// 设置model跟目录
		Element modeRoot = system.element("modeRootPath");
		if (modeRoot != null) {
			String modeRootPath = modeRoot.getTextTrim();
			if (!"".equals(modeRootPath)) {
				ModelReader.setModelLoadRoot(modeRootPath);
				info.append("(modeRootPath = " + modeRootPath + ")");
			}
		}

		// 设置queryLimit
		Element queryLimitElement = system.element("queryLimit");
		if (queryLimitElement != null) {
			String queryLimitvalue = queryLimitElement.getTextTrim();
			if (DataUtil.isNotNull(queryLimitvalue)) {
				if (queryLimitvalue.equals("false")) {
					queryLimit = false;
				}
				info.append("(queryLimit = " + queryLimit + ")");
			}
		}

		// 设置queryLimitsize
		Element queryLimitSizeElement = system.element("queryLimitSize");
		if (queryLimitSizeElement != null) {
			String queryLimitSizeElementValue = queryLimitSizeElement.getTextTrim();
			if (DataUtil.isNotNull(queryLimitSizeElementValue)) {
				queryLimitSize = DataUtil.intValue(queryLimitSizeElementValue);
				info.append("(queryLimitSize = " + queryLimitSize + ")");
			}
		}

		// 设置reportqueryLimitsize
		Element reportQueryLimitSizeElement = system.element("reportQueryLimitSize");
		if (reportQueryLimitSizeElement != null) {
			String reportQueryLimitSizeElementValue = reportQueryLimitSizeElement.getTextTrim();
			if (DataUtil.isNotNull(reportQueryLimitSizeElementValue)) {
				reportQueryLimitSize = DataUtil.intValue(reportQueryLimitSizeElementValue);
				info.append("(reportQueryLimitSize = " + reportQueryLimitSize + ")");
			}
		}

		// 设置启动监听类
		Element listener = system.element("startListener");
		if (listener != null) {
			String className = listener.getTextTrim();
			if (!"".equals(className)) {
				listenerClass = className;
				info.append("(listenerClass = " + listenerClass + ")");
			}
		}

		System.out.println(info);
	}

	public static String getRealPath(String value) {
		value = value.replace(CLASSPATH, getClasspath());
		String projectRoot = getProjectRoot();
		if (projectRoot != null) {
			value = value.replace(PROJECT_ROOT, projectRoot);
		}
		return value;
	}

	private static String getString(Element element, String key) {
		return element.attributeValue(key);
	}

	public static boolean isInited() {
		return inited;
	}

	public static void setInited(boolean inited) {
		DBFoundConfig.inited = inited;
	}

	public static String getClasspath() {
		if (classpath == null || "".equals(classpath)) {
			String cp = Thread.currentThread().getContextClassLoader().getResource("").getFile();
			File file = new File(cp);
			classpath = file.getAbsolutePath();
			classpath = PathFormat.format(classpath);
		}
		return classpath;
	}

	public static String getProjectRoot() {
		if (projectRoot == null || "".equals(projectRoot)) {
			File file = new File(getClasspath());
			try {
				projectRoot = file.getParentFile().getParentFile().getAbsolutePath();
			} catch (Exception e) {
				return null;
			}
			projectRoot = PathFormat.format(projectRoot);
		}
		return projectRoot;
	}

	public static String getConfigFilePath() {
		try {
			if (configFilePath == null || "".equals(configFilePath)) {
				configFilePath = DispatcherFilter.getConfigFilePath();
				configFilePath = PathFormat.format(configFilePath);
			}
			return configFilePath;
		} catch (Throwable e) {
			return null;
		}
	}

	public static void setConfigFilePath(String configFilePath) {
		DBFoundConfig.configFilePath = PathFormat.format(configFilePath);
	}

	public static void setClasspath(String classpath) {
		DBFoundConfig.classpath = PathFormat.format(classpath);
	}

	public static void setProjectRoot(String projectRoot) {
		DBFoundConfig.projectRoot = PathFormat.format(projectRoot);
	}

	public static boolean getQueryLimit() {
		return queryLimit;
	}

	public static int getQueryLimitSize() {
		return queryLimitSize;
	}

	public static int getReportQueryLimitSize() {
		return reportQueryLimitSize;
	}

}
