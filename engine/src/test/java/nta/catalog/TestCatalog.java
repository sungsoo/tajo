package nta.catalog;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

import nta.catalog.Catalog;
import nta.catalog.Column;
import nta.catalog.Schema;
import nta.catalog.proto.TableProtos.DataType;
import nta.catalog.proto.TableProtos.StoreType;
import nta.catalog.proto.TableProtos.TableType;
import nta.conf.NtaConf;
import nta.datum.Datum;
import nta.datum.DatumFactory;
import nta.engine.EngineTestingUtils;
import nta.engine.MiniNtaEngineCluster;
import nta.engine.NtaTestingUtility;
import nta.engine.executor.eval.Expr;
import nta.engine.executor.eval.FieldExpr;
import nta.engine.function.FuncType;
import nta.engine.function.Function;
import nta.storage.CSVFile;
import nta.storage.StorageManager;
import nta.storage.Store;
import nta.util.FileUtil;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.LocalFileSystem;
import org.apache.hadoop.fs.Path;
import org.junit.Before;
import org.junit.Test;

/**
 * 
 * @author Hyunsik Choi
 *
 */
public class TestCatalog {
	NtaConf conf;
	Catalog cat;
	StorageManager manager;
	
	static final String FieldName1="f1";
	static final String FieldName2="f2";
	static final String FieldName3="f3";	
	
	static final DataType Type1 = DataType.BYTE;
	static final DataType Type2 = DataType.INT;
	static final DataType Type3 = DataType.LONG;
	
	static final int Len2 = 10;
	static final int Len3 = 12;
	
	Column field1;
	Column field2;
	Column field3;	
	
	static final String RelName1="rel1";
	static final String RelName2="rel2";
	
	TableMeta rel1;
	TableMeta rel2;
	
	int fid1;
	int fid2;
	int fid3;
	
	int rid1;
	int rid2;
	
	Schema schema1;
	Schema schema2;
	
	NtaTestingUtility util;
	
	final static String TEST_PATH = "/TestCatalog";
	
	public TestCatalog() throws IOException, URISyntaxException {
		
	}
	
	@Before
	public void setUp() throws Exception {
		util = new NtaTestingUtility();
		conf = new NtaConf(util.getConfiguration());
		cat = new Catalog(conf);
		util.startMiniZKCluster();

		EngineTestingUtils.buildTestDir(TEST_PATH);
		
	}
	
	@Test
	public void testGetTable() throws IOException {		
		schema1 = new Schema();
		fid1 = schema1.addColumn(FieldName1, DataType.BYTE);
		fid2 = schema1.addColumn(FieldName2, DataType.INT);
		fid3 = schema1.addColumn(FieldName3, DataType.LONG);
		
		TableMeta meta = new TableMeta("table1");
		meta.setSchema(schema1);
		meta.setStorageType(StoreType.MEM);
		meta.setTableType(TableType.BASETABLE);
		
		assertFalse(cat.existsTable("table1"));
		cat.addTable(meta);
		assertTrue(cat.existsTable("table1"));		
		cat.deleteTable("table1");
		assertFalse(cat.existsTable("table1"));		
	}

/*
	@Test
	public final void testGetRelationString() throws NoSuchTableException {
		assertEquals(catalog.getTableInfo(RelName1).getRelId(),rid1);
		assertEquals(catalog.getTableInfo(RelName2).getRelId(),rid2);
	}

	@Test
	public final void testAddRelation() throws IOException {
		int rid;
		
		Schema s = new Schema();
		s.addField(new Column("age",ColumnType.INT));
		rid = catalog.addRelation("TestCatalog",s, RelationType.BASETABLE, 0, "TestCatalog");
		
		assertEquals(rid,catalog.getTableInfo(rid).getRelId());
		assertEquals("TestCatalog",catalog.getTableInfo(rid).getName());
	}

	@Test
	public final void testDelRelation() throws NoSuchTableException {
		assertNotNull(catalog.getTableInfo(RelName2));
		catalog.deleteRelation(RelName2);
//		assertNull(catalog.getRelation(RelName2));
	}
*/
	
	public static class TestFunc1 extends Function {
		public TestFunc1() {
			super(					
					new Column [] {
							new Column(1, "name", DataType.INT)
					},
					new Expr [] {
							new FieldExpr(DataType.INT, 0, 0)	
					}
			);
		}

		@Override
		public Datum invoke(Datum... datums) {
			return DatumFactory.create(1);
		}

		@Override
		public DataType getResType() {
			return DataType.INT;
		}
	}	

	@Test
	public final void testRegisterFunc() {		
		assertFalse(cat.containFunction("test"));
		FunctionMeta meta = new FunctionMeta("test", TestFunc1.class, FuncType.GENERAL);
		cat.registerFunction(meta);
		assertTrue(cat.containFunction("test"));
		FunctionMeta retrived = cat.getFunctionMeta("test");
		assertEquals(retrived.getName(),"test");
		assertEquals(retrived.getFunctionClass(),TestFunc1.class);
		assertEquals(retrived.getType(),FuncType.GENERAL);
	}

	@Test
	public final void testUnregisterFunc() {
		assertFalse(cat.containFunction("test"));
		FunctionMeta meta = new FunctionMeta("test", TestFunc1.class, FuncType.GENERAL);
		cat.registerFunction(meta);
		assertTrue(cat.containFunction("test"));
		cat.unregisterFunction("test");
		assertFalse(cat.containFunction("test"));
	}
	
	@Test
	public final void testHostsByTable() throws Exception {
		int i, j;
		FSDataOutputStream fos;
		Path tbPath;
		
		util.startMiniCluster(3);
		
		Schema schema = new Schema();
		schema.addColumn("id",DataType.INT);
		schema.addColumn("age",DataType.INT);
		schema.addColumn("name",DataType.STRING);

		TableMeta meta;

		String [] tuples = {
				"1,32,hyunsik",
				"2,29,jihoon",
				"3,28,jimin",
				"4,24,haemi"
		};

		FileSystem fs = util.getMiniDFSCluster().getFileSystem();
		NtaConf conf = new NtaConf(util.getConfiguration());
		Catalog catalog = new Catalog(conf);
		StorageManager sm = new StorageManager(conf, fs);
		Store store;

		int tbNum = 100;
		Random random = new Random();
		int tupleNum;
		
		for (i = 0; i < tbNum; i++) {
			tbPath = new Path(TEST_PATH+"/table"+i);
			if (fs.exists(tbPath)){
				fs.delete(tbPath, true);
			}
			fs.mkdirs(tbPath);
			fos = fs.create(new Path(tbPath, ".meta"));
			meta = new TableMeta();
			meta.setSchema(schema);
			meta.setStorageType(StoreType.CSV);
			meta.setTableType(TableType.BASETABLE);
			meta.putOption(CSVFile.DELIMITER, ",");
			meta.setName("table"+i);
			FileUtil.writeProto(fos, meta.getProto());
			fos.close();
			
			fos = fs.create(new Path(tbPath, "data/table.csv"));
			tupleNum = random.nextInt(49)+100001;
			for (j = 0; j < tupleNum; j++) {
				fos.writeBytes(tuples[0]+"\n");
			}
			fos.close();

			store = sm.open(tbPath.toUri());
			meta.setStore(store);
			catalog.addTable(meta);
		}
		
		catalog.updateAllTabletServingInfo();
		
		Collection<TableInfo> tables = catalog.getTableInfos();
		Iterator<TableInfo> it = tables.iterator();
		List<TabletInfo> tabletInfoList;
		int cnt = 0;
		int len = 0;
		TableInfo tableInfo;
		FileStatus fileStatus;
		while (it.hasNext()) {
			tableInfo = it.next();
			tabletInfoList = catalog.getHostByTable(tableInfo.getName());
			if (tabletInfoList != null) {
				cnt++;
				len = 0;
				for (i = 0; i < tabletInfoList.size(); i++) {
					len += tabletInfoList.get(i).getTablet().getLength();
				}
				fileStatus = fs.getFileStatus(new Path(tableInfo.getStore().getURI()+"/data/table.csv"));
				assertEquals(len, fileStatus.getLen());
			}
		}
		
		util.shutdownMiniCluster();
		assertEquals(tbNum, cnt);
	}
}