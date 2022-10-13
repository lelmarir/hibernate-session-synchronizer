package com.example;

import static javax.persistence.GenerationType.IDENTITY;

import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.Id;
import javax.persistence.ManyToOne;

@Entity
public class Bar {

	@Id
	@GeneratedValue(strategy = IDENTITY)
	public Integer id;

	@ManyToOne()
	private Foo foo;

	public Bar() {
		// hibernate
	}

	public Foo getFoo() {
		return foo;
	}

	public void setFoo(Foo foo) {
		this.foo = foo;
	}

	@Override
	public String toString() {
		return "Bar [id=" + id + ", foo=" + foo + "]";
	}

}
