import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import type { DataSource } from "@/lib/types";
import { DataSourceTable, dataSourceView } from "../DataSourceTable";

const HR: DataSource = {
  id: "hr",
  url: "jdbc:postgresql://acme-db:5432/acme",
  secret_ref: "aisolate-ds-hr-a1b2c3",
};
const OPEN: DataSource = { id: "open", url: "jdbc:postgresql://acme-db:5432/open", secret_ref: "" };

describe("DataSourceTable", () => {
  it("givenActions_whenListed_thenAMenuOnEveryRowAndEditOnTheOwnersOwn", () => {
    // Arrange
    const actions = {
      onView: () => undefined,
      edit: { onEdit: () => undefined, busy: false, onRemove: () => undefined },
    };

    // Act
    const html = renderToStaticMarkup(
      createElement(DataSourceTable, { rows: [HR, OPEN], actions, editable: (r) => r.id === "hr" }),
    );

    // Assert: two menus, one Edit (hr's)
    expect(html).toMatch(/<th[^>]*>Actions<\/th>/);
    expect(html.match(/aria-label="Actions for data source/g)).toHaveLength(2);
    expect(html.match(/>Edit</g)).toHaveLength(1);
  });

  it("givenADataSourceWithALogin_whenViewed_thenIdAndUrlAndBothKeys", () => {
    // Arrange / Act
    const view = dataSourceView(HR);

    // Assert
    expect(view).toEqual({
      title: "View data source",
      fields: [
        { label: "Id", value: "hr" },
        { label: "URL", value: "jdbc:postgresql://acme-db:5432/acme" },
      ],
      secretRef: "aisolate-ds-hr-a1b2c3",
      keys: [
        { name: "username", label: "Username" },
        { name: "password", label: "Password" },
      ],
    });
  });

  it("givenADataSourceWithoutALogin_whenViewed_thenNoSecretRef", () => {
    // Arrange / Act
    const view = dataSourceView(OPEN);

    // Assert
    expect(view.secretRef).toBeNull();
  });
});
