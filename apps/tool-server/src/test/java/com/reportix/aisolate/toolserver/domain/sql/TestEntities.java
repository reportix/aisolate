package com.reportix.aisolate.toolserver.domain.sql;

import com.reportix.aisolate.sdk.Column;
import com.reportix.aisolate.sdk.Entity;

/** The demo entity, kept here so the compiler and argument tests have a realistic allow-list. */
final class TestEntities {

  static final Entity HR_COMPENSATION =
      new Entity(
              "hr.compensation",
              "hr.compensation c JOIN hr.employees e ON e.id = c.employee_id",
              "employee_id",
              new Column("employee_id", "c.employee_id", Column.Type.NUMBER),
              new Column("full_name", "e.full_name", Column.Type.TEXT),
              new Column("department", "e.department", Column.Type.TEXT),
              new Column("title", "e.title", Column.Type.TEXT),
              new Column("gross_salary", "c.gross_salary", Column.Type.NUMBER)
                  .withUnitFrom("currency"),
              new Column("currency", "c.currency", Column.Type.TEXT),
              new Column("bonus_2025", "c.bonus_2025", Column.Type.NUMBER)
                  .withUnit("EUR")
                  .withDecimals(2),
              new Column("iban", "c.iban", Column.Type.TEXT),
              new Column("internal_notes", "c.internal_notes", Column.Type.TEXT))
          .withSubjectName("full_name");

  private TestEntities() {}
}
