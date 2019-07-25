package org.openmbee.sdvc.jupyter.controllers;

import java.util.List;
import org.openmbee.sdvc.crud.controllers.elements.ElementsRequest;
import org.openmbee.sdvc.json.ElementJson;

public class NotebooksRequest extends ElementsRequest {

    private List<ElementJson> notebooks;

    public List<ElementJson> getNotebooks() {
        return notebooks;
    }

    public void setNotebooks(List<ElementJson> notebooks) {
        this.notebooks = notebooks;
    }

    @Override
    public List<ElementJson> getElements() {
        return notebooks;
    }

    @Override
    public void setElements(List<ElementJson> elements) {
        this.notebooks = elements;
    }
}