package com.tightdb.lib;

import static org.testng.AssertJUnit.assertEquals;

import java.util.Date;

import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.tightdb.test.TestEmployeeQuery;
import com.tightdb.test.TestEmployeeRow;
import com.tightdb.test.TestEmployeeTable;
import com.tightdb.test.TestEmployeeView;

@Test
public class TableDataOperationsTest extends AbstractDataOperationsTest {

	private TestEmployeeTable employees;

	@Override
	protected AbstractTableOrView<TestEmployeeRow, TestEmployeeView, TestEmployeeQuery> getEmployees() {
		return employees;
	}

	@BeforeMethod
	public void init() {
		employees = new TestEmployeeTable();

		employees.add(NAME0, "Doe", 10000, true, new byte[] { 1, 2, 3 },
				new Date(), "extra");
		employees.add(NAME2, "B. Good", 10000, true, new byte[] { 1, 2, 3 },
				new Date(), true);
		employees.insert(1, NAME1, "Mihajlovski", 30000, false, new byte[] { 4,
				5 }, new Date(), 1234);
	}

	private void setAndTestValue(long val) {
		employees.at(1).setSalary(val);
		assertEquals(val, employees.at(1).getSalary());
	}

	@Test
	public void shouldStoreValues() {
		setAndTestValue(Integer.MAX_VALUE);
		setAndTestValue(Integer.MIN_VALUE);

		setAndTestValue(Long.MAX_VALUE);
		setAndTestValue(Long.MIN_VALUE);
	}

}
